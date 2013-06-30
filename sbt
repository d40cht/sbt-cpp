export LD_LIBRARY_PATH=bin
java -Xmx4000M -XX:OnOutOfMemoryError="kill -3 %p" -XX:+HeapDumpOnOutOfMemoryError -XX:MaxPermSize=256M -XX:+AggressiveOpts -XX:+DoEscapeAnalysis -XX:+UseCompressedOops -server -Djline.terminal=jline.UnixTerminal -jar `dirname $0`/sbt-launch.jar "$@"
