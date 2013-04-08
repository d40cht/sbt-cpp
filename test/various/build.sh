#!/bin/bash

set -e

rm -rf ~/.sbt/staging
../../sbt "build-environment Debug_Gcc_LinuxPC" clean compile test "build-environment Release_Gcc_LinuxPC" clean compile test
#../../sbt 
