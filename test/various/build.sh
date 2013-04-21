#!/bin/bash

set -e

../../sbt "native-build-environment Debug_Gcc_LinuxPC" native-clean-all clean compile test "native-build-environment Release_Gcc_LinuxPC" clean compile test
