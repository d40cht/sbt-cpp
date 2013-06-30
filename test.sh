#!/bin/bash

set -e
./sbt publish publish-local

cd test/various
./build.sh

