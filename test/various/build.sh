#!/bin/bash

set -e

rm -rf target

echo "Debug_Gcc_LinuxPC"
../../sbt "native-build-configuration Debug_Gcc_LinuxPC" native-clean-all clean compile test
echo "Release_Gcc_LinuxPC"
../../sbt "native-build-configuration Release_Gcc_LinuxPC" clean compile test
echo "Debug_Clang_LinuxPC"
../../sbt "native-build-configuration Debug_Clang_LinuxPC" clean compile test
echo "Release_Gcc_LinuxPC"
../../sbt "native-build-configuration Release_Clang_LinuxPC" clean compile test

