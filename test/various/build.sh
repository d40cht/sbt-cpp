#!/bin/bash

set -e

../../sbt "build-environment Debug_Gcc_LinuxPC" clean compile test "build-environment Release_Gcc_LinuxPC" clean compile test
