#!/bin/bash

set -e

rm -rf target

../../sbt "native-build-configuration Gcc_LinuxPC_Debug" native-clean-all clean compile test
../../sbt "native-build-configuration Gcc_LinuxPC_Release" clean compile test
../../sbt "native-build-configuration Clang_LinuxPC_Debug" clean compile test
../../sbt "native-build-configuration Clang_LinuxPC_Release" clean compile test

