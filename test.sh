#!/bin/bash

set -e

./sbt publish publish-local

pushd .
cd test/various
./build.sh
popd

pushd .
cd samples/helloworld
sbt "native-build-configuration Gcc_LinuxPC_Debug" "run Baz"
popd

pushd .
cd samples/simpletest
sbt "native-build-configuration Gcc_LinuxPC_Debug" test
popd


