package org.apache.mesos.specification.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.commons.io.FileUtils;
import org.apache.mesos.offer.TaskUtils;
import org.apache.mesos.specification.DefaultServiceSpec;
import org.apache.mesos.specification.ServiceSpec;

import java.io.File;
import java.nio.charset.Charset;

/**
 * Generates {@link ServiceSpec} from a given YAML definition.
 */
public class YAMLServiceSpecFactory {
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    public static final RawServiceSpecification generateRawSpecFromYAML(File pathToYaml) throws Exception {
        return generateRawSpecFromYAML(FileUtils.readFileToString(pathToYaml, Charset.forName("UTF-8")));
    }

    public static final RawServiceSpecification generateRawSpecFromYAML(final String yaml) throws Exception {
        final String yamlWithEnv = TaskUtils.applyEnvToMustache(yaml, System.getenv());
        if (!TaskUtils.isMustacheFullyRendered(yamlWithEnv)) {
            throw new IllegalStateException("YAML contains unsubstitued variables.");
        }
        return YAML_MAPPER.readValue(yamlWithEnv.getBytes(), RawServiceSpecification.class);
    }

    public static final DefaultServiceSpec generateSpecFromYAML(RawServiceSpecification rawServiceSpecification)
            throws Exception {
        return YAMLToInternalMappers.from(rawServiceSpecification);
    }
}