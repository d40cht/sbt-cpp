#!/bin/bash

set -e

rm -rf target

../../sbt "native-build-configuration Debug_Gcc_LinuxPC" native-clean-all clean compile test
../../sbt "native-build-configuration Release_Gcc_LinuxPC" clean compile test
../../sbt "native-build-configuration Debug_Clang_LinuxPC" clean compile test
../../sbt "native-build-configuration Release_Clang_LinuxPC" clean compile test

