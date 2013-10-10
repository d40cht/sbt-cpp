#!/bin/bash

set -e

./sbt publish publish-local

pushd .
cd test/various
./build.sh
popd

pushd .
cd samples/helloworld
../../sbt "nativeBuildConfiguration Gcc_LinuxPC_Debug" "run Baz"
popd

pushd .
cd samples/simpletest
../../sbt "nativeBuildConfiguration Gcc_LinuxPC_Debug" test
popd


