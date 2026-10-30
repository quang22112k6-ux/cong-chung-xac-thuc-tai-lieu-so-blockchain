#!/usr/bin/env sh
# Biên dịch và chạy Blockchain Notary (cần JDK 17 trở lên, không cần thư viện ngoài).
set -e
cd "$(dirname "$0")"
JAVAC="javac"
command -v javac >/dev/null 2>&1 || JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main"
rm -rf out && mkdir -p out
$JAVAC -encoding UTF-8 -d out $(find src/main/java -name '*.java')
cp -r src/main/resources/* out/
exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp out vn.edu.notary.Main "$@"
