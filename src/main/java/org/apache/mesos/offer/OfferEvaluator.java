package org.apache.mesos.offer;

import org.apache.mesos.Protos;
import org.apache.mesos.Protos.ExecutorInfo;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.Resource;
import org.apache.mesos.Protos.Resource.DiskInfo;
import org.apache.mesos.Protos.Resource.DiskInfo.Persistence;
import org.apache.mesos.Protos.Resource.ReservationInfo;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.Protos.Value;
import org.apache.mesos.executor.ExecutorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.TextFormat;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The OfferEvaluator processes {@link Offer}s and produces {@link OfferRecommendation}s.
 * The determination of what {@link OfferRecommendation}s, if any should be made are made
 * in reference to the {@link OfferRequirement with which it was constructed.    In the
 * case where an OfferRequirement has not been provided no {@link OfferRecommendation}s
 * are ever returned.
 */
public class OfferEvaluator {
    private static final Logger logger = LoggerFactory.getLogger(OfferEvaluator.class);

    public OfferEvaluator() { }

    public List<OfferRecommendation> evaluate(OfferRequirement offerRequirement, List<Offer> offers) {
        for (Offer offer : offers) {
            List<OfferRecommendation> recommendations = evaluate(offerRequirement, offer);
            if (recommendations != null && !recommendations.isEmpty()) {
                return recommendations;
            }
        }
        return Collections.emptyList();
    }

    private boolean offerMeetsPlacementConstraints(OfferRequirement offerReq, Offer offer) {
        if (offerReq.getAvoidAgents().contains(offer.getSlaveId())) {
            return false;
        }

        if (offerReq.getColocateAgents().size() > 0 &&
                !offerReq.getColocateAgents().contains(offer.getSlaveId())) {
            return false;
        }

        return true;
    }

    private boolean hasExpectedExecutorId(Offer offer, Protos.ExecutorID executorID) {
        for (Protos.ExecutorID execId : offer.getExecutorIdsList()) {
            if (execId.equals(executorID)) {
                return true;
            }
        }

        return false;
    }

    public List<OfferRecommendation> evaluate(OfferRequirement offerRequirement, Offer offer) {
        if (!offerMeetsPlacementConstraints(offerRequirement, offer)) {
            return Collections.emptyList();
        }

        MesosResourcePool pool = new MesosResourcePool(offer);

        List<OfferRecommendation> unreserves = new ArrayList<>();
        List<OfferRecommendation> reserves = new ArrayList<>();
        List<OfferRecommendation> creates = new ArrayList<>();
        List<OfferRecommendation> launches = new ArrayList<>();

        ExecutorRequirement execReq = offerRequirement.getExecutorRequirement();
        FulfilledRequirement fulfilledExecutorRequirement = null;
        Map<String, Long> servicePorts = null;
        if (execReq != null) {
            if (execReq.desiresResources() || execReq.getExecutorInfo().getExecutorId().getValue().isEmpty()) {
                servicePorts = getServicePortAssignments(offerRequirement.getServicePorts(), offer, execReq);
                Collection<ResourceRequirement> resReqs = execReq.getResourceRequirements();

                if (offerRequirement.getServicePorts() != null &&
                        offerRequirement.getServicePorts().size() > 0 && servicePorts == null) {
                    return Collections.emptyList();
                } else if (servicePorts != null) {
                    resReqs = mergeServicePorts(resReqs, servicePorts);
                }

                fulfilledExecutorRequirement = FulfilledRequirement.fulfillRequirement(
                        resReqs,
                        offer,
                        pool);

                if (fulfilledExecutorRequirement == null) {
                    return Collections.emptyList();
                }

                unreserves.addAll(fulfilledExecutorRequirement.getUnreserveRecommendations());
                reserves.addAll(fulfilledExecutorRequirement.getReserveRecommendations());
                creates.addAll(fulfilledExecutorRequirement.getCreateRecommendations());
            } else {
                Protos.ExecutorID expectedExecutorId = execReq.getExecutorInfo().getExecutorId();
                if (!hasExpectedExecutorId(offer, expectedExecutorId)) {
                    logger.info("Offer: '{}' does not contain the needed ExecutorID: '{}'",
                                    offer.getId().getValue(), expectedExecutorId.getValue());
                    return Collections.emptyList();
                }
            }
        }

        ExecutorInfo execInfo = null;
        if (execReq != null) {
            execInfo = execReq.getExecutorInfo();
            if (execInfo.hasCommand() && servicePorts != null && execInfo.getExecutorId().getValue().isEmpty()) {
                ExecutorInfo.Builder execBuilder = ExecutorInfo.newBuilder(execInfo);

                if (execInfo.hasCommand() && servicePorts != null) {
                    Protos.Environment.Builder envBuilder;
                    if (execInfo.getCommand().hasEnvironment()) {
                        envBuilder = Protos.Environment.newBuilder(execInfo.getCommand().getEnvironment());
                    } else {
                        envBuilder = Protos.Environment.newBuilder();
                    }

                    for (Map.Entry<String, Long> e : servicePorts.entrySet()) {
                        envBuilder.addVariables(
                                Protos.Environment.Variable.newBuilder()
                                        .setName(e.getKey())
                                        .setValue(e.getValue().toString()));
                    }

                    execBuilder.setCommand(
                            Protos.CommandInfo.newBuilder(execInfo.getCommand()).setEnvironment(envBuilder));
                }

                execInfo = execBuilder.setExecutorId(ExecutorUtils.toExecutorId(execInfo.getName())).build();
            }
        }

        for (TaskRequirement taskReq : offerRequirement.getTaskRequirements()) {
            FulfilledRequirement fulfilledTaskRequirement = FulfilledRequirement.fulfillRequirement(
                    taskReq.getResourceRequirements(), offer, pool);

            if (fulfilledTaskRequirement == null) {
                return Collections.emptyList();
            }

            unreserves.addAll(fulfilledTaskRequirement.getUnreserveRecommendations());
            reserves.addAll(fulfilledTaskRequirement.getReserveRecommendations());
            creates.addAll(fulfilledTaskRequirement.getCreateRecommendations());

            launches.add(
                new LaunchOfferRecommendation(
                    offer,
                    getFulfilledTaskInfo(
                        taskReq,
                        fulfilledTaskRequirement,
                        execInfo,
                        fulfilledExecutorRequirement)));
        }

        List<OfferRecommendation> recommendations = new ArrayList<OfferRecommendation>();
        recommendations.addAll(unreserves);
        recommendations.addAll(reserves);
        recommendations.addAll(creates);
        recommendations.addAll(launches);

        return recommendations;
    }

    private Collection<ResourceRequirement> mergeServicePorts(
            Collection<ResourceRequirement> resourceRequirements, Map<String, Long> servicePorts) {
        ResourceRequirement portRequirement = null;
        for (ResourceRequirement r : resourceRequirements) {
            if (r.getName() == "ports") {
                portRequirement = r;
            }
        }

        Value.Ranges.Builder servicePortRanges = Value.Ranges.newBuilder();
        for (Long p : servicePorts.values()) {
            servicePortRanges.addRange(Value.Range.newBuilder().setBegin(p).setEnd(p));
        }

        Resource portResource = null;
        if (portRequirement != null) {
            portResource = Resource.newBuilder(portRequirement.getResource())
                                   .mergeRanges(servicePortRanges.build())
                                   .build();
        } else {
            Resource reference = resourceRequirements.iterator().next().getResource();
            Resource.Builder resourceBuilder = Resource.newBuilder()
                                   .setName("ports")
                                   .setType(Value.Type.RANGES)
                                   .setRanges(servicePortRanges.build());

            if (reference.hasReservation()) {
                resourceBuilder.setReservation(reference.getReservation());
            }
            if (reference.hasRole()) {
                resourceBuilder.setRole(reference.getRole());
            }
            portResource = resourceBuilder.build();
        }

        return Stream.concat(
                        resourceRequirements.stream().filter(r -> r.getName() != "ports"),
                        Stream.of(new ResourceRequirement(portResource)))
                     .collect(Collectors.toList());
    }

    private Boolean isInRanges(Long value, Value.Ranges ranges) {
        Boolean isInRange = false;
        for (Value.Range r : ranges.getRangeList()) {
            if (value < r.getBegin()) {
                break;
            } else if (value >= r.getBegin() && value <= r.getEnd()) {
                isInRange = true;
                break;
            }
        }

        return isInRange;
    }

    private Map<String, Long> getServicePortAssignments(
            Collection<String> servicePorts, Offer offer, ExecutorRequirement execReq) {
        Iterator<Value.Range> offerRanges = null;

        for (Resource r : offer.getResourcesList()) {
            if (r.getName() == "ports") {
                offerRanges = r.getRanges().getRangeList().iterator();
            }
        }

        Value.Ranges requiredRanges = null;
        for (ResourceRequirement r : execReq.getResourceRequirements()) {
            if (r.getName() == "ports") {
                requiredRanges = r.getResource().getRanges();
            }
        }

        if (offerRanges == null) {
            return null;
        }

        Map<String, Long> assignments = new HashMap<>();
        Iterator<String> portNameIterator = servicePorts.iterator();
        Value.Range currentOffer = offerRanges.next();
        String currentPortName = portNameIterator.next();
        Long currentPort = currentOffer.getBegin();

        while (assignments.size() < servicePorts.size()) {
            if (currentPort > currentOffer.getEnd()) {
                if (!offerRanges.hasNext()) {
                    // We weren't offered enough ports to assign all of our service ports, so abort and return null.
                    break;
                }
                currentOffer = offerRanges.next();
            }
            if (requiredRanges == null || !isInRanges(currentPort, requiredRanges)) {
                assignments.put(currentPortName, currentPort);
                currentPortName = portNameIterator.hasNext() ? portNameIterator.next() : null;
                ++currentPort;
            }
        }

        return assignments.size() == servicePorts.size() ? assignments : null;
    }

    private static class FulfilledRequirement {
        private List<Resource> fulfilledResources = new ArrayList<Resource>();
        private List<OfferRecommendation> unreserveRecommendations = new ArrayList<OfferRecommendation>();
        private List<OfferRecommendation> reserveRecommendations = new ArrayList<OfferRecommendation>();
        private List<OfferRecommendation> createRecommendations = new ArrayList<OfferRecommendation>();

        private FulfilledRequirement(
                List<Resource> fulfilledResources,
                List<OfferRecommendation> unreserveRecommendations,
                List<OfferRecommendation> reserveRecommendations,
                List<OfferRecommendation> createRecommendations) {

            this.fulfilledResources = fulfilledResources;
            this.unreserveRecommendations = unreserveRecommendations;
            this.reserveRecommendations = reserveRecommendations;
            this.createRecommendations = createRecommendations;
        }

        public static FulfilledRequirement fulfillRequirement(
                Collection<ResourceRequirement> resourceRequirements,
                Offer offer,
                MesosResourcePool pool) {

            List<Resource> fulfilledResources = new ArrayList<Resource>();
            List<OfferRecommendation> unreserveRecommendations = new ArrayList<OfferRecommendation>();
            List<OfferRecommendation> reserveRecommendations = new ArrayList<OfferRecommendation>();
            List<OfferRecommendation> createRecommendations = new ArrayList<OfferRecommendation>();

            for (ResourceRequirement resReq : resourceRequirements) {
                MesosResource mesRes = pool.consume(resReq);
                if (mesRes == null) {
                    logger.warn("Failed to satisfy resource requirement: {}",
                            TextFormat.shortDebugString(resReq.getResource()));
                    return null;
                } else {
                    logger.info("Satisfying resource requirement: {}\nwith resource: {}",
                            TextFormat.shortDebugString(resReq.getResource()),
                            TextFormat.shortDebugString(mesRes.getResource()));
                }

                Resource fulfilledResource = getFulfilledResource(resReq, mesRes);
                if (resReq.expectsResource()) {
                    logger.info("Expects Resource");
                    // Compute any needed resource pool consumption / release operations
                    // as well as any additional needed Mesos Operations.    In the case
                    // where a requirement has changed for an Atomic resource, no Operations
                    // can be performed because the resource is Atomic.
                    if (expectedValueChanged(resReq, mesRes) && !mesRes.isAtomic()) {
                        Value reserveValue = ValueUtils.subtract(resReq.getValue(), mesRes.getValue());
                        Value unreserveValue = ValueUtils.subtract(mesRes.getValue(), resReq.getValue());

                        if (ValueUtils.compare(unreserveValue, ValueUtils.getZero(unreserveValue.getType())) > 0) {
                            logger.info("Updates reserved resource with less reservation");
                            Resource unreserveResource = ResourceUtils.getDesiredResource(
                                    resReq.getRole(),
                                    resReq.getPrincipal(),
                                    resReq.getName(),
                                    unreserveValue);
                            unreserveResource = ResourceUtils.setResourceId(unreserveResource, resReq.getResourceId());

                            pool.release(new MesosResource(
                                    ResourceUtils.getUnreservedResource(resReq.getName(), unreserveValue)));
                            unreserveRecommendations.add(new UnreserveOfferRecommendation(offer, unreserveResource));
                            fulfilledResource = getFulfilledResource(resReq, new MesosResource(resReq.getResource()));
                        }

                        if (ValueUtils.compare(reserveValue, ValueUtils.getZero(reserveValue.getType())) > 0) {
                            logger.info("Updates reserved resource with additional reservation");
                            Resource reserveResource = ResourceUtils.getDesiredResource(
                                    resReq.getRole(),
                                    resReq.getPrincipal(),
                                    resReq.getName(),
                                    reserveValue);

                            if (pool.consume(new ResourceRequirement(reserveResource)) != null) {
                                reserveResource = ResourceUtils.setResourceId(reserveResource, resReq.getResourceId());
                                reserveRecommendations.add(new ReserveOfferRecommendation(offer, reserveResource));
                                fulfilledResource = getFulfilledResource(
                                        resReq, new MesosResource(resReq.getResource()));
                            } else {
                                logger.warn("Insufficient resources to increase resource usage.");
                                return null;
                            }
                        }
                    }
                } else {
                    if (resReq.reservesResource()) {
                        logger.info("Reserves Resource");
                        reserveRecommendations.add(new ReserveOfferRecommendation(offer, fulfilledResource));
                    }

                    if (resReq.createsVolume()) {
                        logger.info("Creates Volume");
                        createRecommendations.add(new CreateOfferRecommendation(offer, fulfilledResource));
                    }
                }

                logger.info("Fulfilled resource: {}", TextFormat.shortDebugString(fulfilledResource));
                fulfilledResources.add(fulfilledResource);
            }

            return new FulfilledRequirement(
                    fulfilledResources,
                    unreserveRecommendations,
                    reserveRecommendations,
                    createRecommendations);
        }

        public List<Resource> getFulfilledResources() {
            return fulfilledResources;
        }

        public List<OfferRecommendation> getUnreserveRecommendations() {
            return unreserveRecommendations;
        }

        public List<OfferRecommendation> getReserveRecommendations() {
            return reserveRecommendations;
        }

        public List<OfferRecommendation> getCreateRecommendations() {
            return createRecommendations;
        }
    }


    private static boolean expectedValueChanged(ResourceRequirement resReq, MesosResource mesRes) {
        return !ValueUtils.equal(resReq.getValue(), mesRes.getValue());
    }

    private static Resource getFulfilledResource(ResourceRequirement resReq, MesosResource mesRes) {
        Resource.Builder builder = Resource.newBuilder(mesRes.getResource());
        builder.setRole(resReq.getResource().getRole());

        ReservationInfo resInfo = getFulfilledReservationInfo(resReq, mesRes);
        if (resInfo != null) {
            builder.setReservation(resInfo);
        }

        DiskInfo diskInfo = getFulfilledDiskInfo(resReq, mesRes);
        if (diskInfo != null) {
            builder.setDisk(diskInfo);
        }

        return builder.build();
    }

    private static ReservationInfo getFulfilledReservationInfo(ResourceRequirement resReq, MesosResource mesRes) {
        if (!resReq.reservesResource()) {
            return null;
        } else {
            ReservationInfo.Builder resBuilder = ReservationInfo.newBuilder(resReq.getResource().getReservation());
            resBuilder.setLabels(
                    ResourceUtils.setResourceId(
                        resReq.getResource().getReservation().getLabels(),
                        UUID.randomUUID().toString()));
            return resBuilder.build();
        }
    }

    private static DiskInfo getFulfilledDiskInfo(ResourceRequirement resReq, MesosResource mesRes) {
        if (!resReq.getResource().hasDisk()) {
            return null;
        }

        DiskInfo.Builder builder = DiskInfo.newBuilder(resReq.getResource().getDisk());
        if (mesRes.getResource().getDisk().hasSource()) {
            builder.setSource(mesRes.getResource().getDisk().getSource());
        }

        Persistence persistence = getFulfilledPersistence(resReq);
        if (persistence != null) {
            builder.setPersistence(persistence);
        }

        return builder.build();
    }

    private static Persistence getFulfilledPersistence(ResourceRequirement resReq) {
        if (!resReq.createsVolume()) {
            return null;
        } else {
            String persistenceId = UUID.randomUUID().toString();
            return Persistence.newBuilder(resReq.getResource().getDisk().getPersistence()).setId(persistenceId).build();
        }
    }

    private TaskInfo getFulfilledTaskInfo(
            TaskRequirement taskReq,
            FulfilledRequirement fulfilledTaskRequirement,
            ExecutorInfo execInfo,
            FulfilledRequirement fulfilledExecutorRequirement) {

        TaskInfo taskInfo = taskReq.getTaskInfo();
        List<Resource> fulfilledTaskResources = fulfilledTaskRequirement.getFulfilledResources();
        TaskInfo.Builder taskBuilder =
            TaskInfo.newBuilder(taskInfo)
            .clearResources()
            .addAllResources(fulfilledTaskResources);

        if (execInfo != null) {
            ExecutorInfo.Builder execBuilder =
                            ExecutorInfo.newBuilder(execInfo)
                                            .clearResources();

            if (fulfilledExecutorRequirement != null) {
                List<Resource> fulfilledExecutorResources = fulfilledExecutorRequirement.getFulfilledResources();
                execBuilder.addAllResources(fulfilledExecutorResources);
            } else {
                execBuilder.addAllResources(execInfo.getResourcesList());
            }

            taskBuilder.setExecutor(execBuilder.build());
        }

        return taskBuilder.build();
    }
}
