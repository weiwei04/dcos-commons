#!/bin/bash

# abort script at first error:
set -e

error_msg() {
    echo "---"
    echo "Failed to get/start the cluster: Exited early at $0:L$1"
    echo "To try again, re-run this script."
    echo "---"
}
trap 'error_msg ${LINENO}' ERR

BOX_PATH=${BOX_PATH:=dcos-docker-sdk.box}

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd $SCRIPT_DIR
VAGRANT_DIR=$SCRIPT_DIR/tools/vagrant/

cd $VAGRANT_DIR

# Manually download the base box image. This allows users to provide
# a custom path to a local pre-downloaded copy (eg usb stick)
if [ $(vagrant box list | grep dcos-docker-sdk | wc -l) -eq 0 ]; then
    if [ ! -f ${BOX_PATH} ]; then
        echo "### Downloading base box image"
        curl -O http://example.com/$(basename ${BOX_PATH})
    fi
    vagrant box add --name dcos-docker-sdk ${BOX_PATH}
fi

echo "### Launching VM against image: ${BOX_PATH}"
vagrant up

echo "### Starting DC/OS Cluster within VM: ~/start-dcos.sh"
vagrant ssh -c '~/start-dcos.sh'

${SCRIPT_DIR}/node-route.sh

echo "----"
echo "Dashboard URL:  http://172.17.0.2 (may need to relaunch browser)"
echo ""
echo "Log into VM:    pushd ${VAGRANT_DIR} && vagrant ssh && popd"
echo "Build example:  Log into VM, then: cd /dcos-commons/frameworks/helloworld && ./build.sh local"
echo ""
echo "Rebuild routes: ${SCRIPT_DIR}/node-route.sh"
echo "Delete VM:      pushd ${VAGRANT_DIR} && vagrant destroy && popd"
echo "---"
