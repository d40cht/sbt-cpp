export LD_LIBRARY_PATH=bin
#java -Xmx6800M -XX:MaxPermSize=256M -XX:+AggressiveOpts -XX:+DoEscapeAnalysis -XX:+UseCompressedOops -server -jar `dirname $0`/sbt-launch.jar "$@"
java -Xmx4000M -XX:OnOutOfMemoryError="kill -3 %p" -XX:+HeapDumpOnOutOfMemoryError -XX:MaxPermSize=256M -XX:+AggressiveOpts -XX:+DoEscapeAnalysis -XX:+UseCompressedOops -server -jar `dirname $0`/sbt-launch.jar "$@"
