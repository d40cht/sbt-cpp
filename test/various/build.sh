#!/bin/bash

set -e

../../sbt "native-build-configuration Debug_Gcc_LinuxPC" native-clean-all clean compile test "native-build-configuration Release_Gcc_LinuxPC" clean compile test

