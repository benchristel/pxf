#!/bin/bash

mkdir -p /usr/local/greenplum-db-devel
chown -R gpadmin:gpadmin /usr/local/greenplum-db-devel

# TODO: check if gpadmin-limits.conf already exists and bail out if it does
>/etc/security/limits.d/gpadmin-limits.conf cat <<-EOF
gpadmin soft core unlimited
gpadmin soft nproc 131072
gpadmin soft nofile 65536
EOF

>>/home/gpadmin/.bash_profile cat <<EOF
export PS1="[\u@\h \W]\$ "
source /opt/rh/devtoolset-6/enable
export HADOOP_ROOT=~/workspace/singlecluster
export PXF_HOME=/usr/local/greenplum-db-devel/pxf
export GPHD_ROOT=~/workspace/singlecluster
export BUILD_PARAMS="-x test"
export LANG=en_US.UTF-8
export JAVA_HOME=/etc/alternatives/java_sdk
export SLAVES=1
export GOPATH=/opt/go
export PATH=\${PXF_HOME}/bin:\${GPHD_ROOT}/hadoop/bin:\${GOPATH}/bin:/usr/local/go/bin:\$PATH
EOF
