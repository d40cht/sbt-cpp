#!/bin/bash

set -e

rm -rf target

../../sbt "native-build-configuration Gcc_LinuxPC_Debug" compile test
../../sbt "native-build-configuration Gcc_LinuxPC_Release" compile test
../../sbt "native-build-configuration Clang_LinuxPC_Debug" compile test
../../sbt "native-build-configuration Clang_LinuxPC_Release" compile test

