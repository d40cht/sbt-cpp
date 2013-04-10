#!/bin/bash

set -e

../../sbt "build-environment Debug_Gcc_LinuxPC" native-clean-all clean compile test "build-environment Release_Gcc_LinuxPC" clean compile test
