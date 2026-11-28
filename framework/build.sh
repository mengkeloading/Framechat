#!/bin/sh
# 编译 + 跑自测。
#
# 工具链不装在系统里：
#   - JDK 必须放在 /tmp：工作区挂载会丢掉可执行位，java 在那里跑不起来。
#   - kotlinc 用 Maven 上的编译器 jar，直接调 K2JVMCompiler，省掉整个发行包。
set -e

ROOT=$(cd "$(dirname "$0")" && pwd)
TOOLS=${MYCHAT_TOOLS:-/tmp/mychat-tools}
LIB="$ROOT/.tools/lib"
M=https://repo1.maven.org/maven2

JARS="
org/jetbrains/kotlin/kotlin-compiler-embeddable/2.1.20/kotlin-compiler-embeddable-2.1.20.jar
org/jetbrains/kotlin/kotlin-stdlib/2.1.20/kotlin-stdlib-2.1.20.jar
org/jetbrains/kotlin/kotlin-script-runtime/2.1.20/kotlin-script-runtime-2.1.20.jar
org/jetbrains/kotlin/kotlin-reflect/1.6.10/kotlin-reflect-1.6.10.jar
org/jetbrains/kotlin/kotlin-daemon-embeddable/2.1.20/kotlin-daemon-embeddable-2.1.20.jar
org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar
org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.8.0/kotlinx-coroutines-core-jvm-1.8.0.jar
org/jetbrains/annotations/13.0/annotations-13.0.jar
org/mozilla/rhino/1.7.15/rhino-1.7.15.jar
"

mkdir -p "$LIB"
for a in $JARS; do
  f="$LIB/$(basename "$a")"
  [ -f "$f" ] || curl -sSL --retry 3 -o "$f" "$M/$a"
done

if [ -n "${MYCHAT_JDK:-}" ]; then
  # CI / x86_64 上直接用现成 JDK（如 setup-java 装的），不必下 musl 版
  JAVA="$MYCHAT_JDK/bin/java"
else
  if [ ! -f "$TOOLS/jdk.path" ]; then
    echo "== 取 JDK 17 (musl)"
    mkdir -p "$TOOLS"
    [ -f "$TOOLS/zulu.tar.gz" ] || curl -sSL --retry 3 -o "$TOOLS/zulu.tar.gz" \
      https://cdn.azul.com/zulu/bin/zulu17.70.201-ca-jdk17.0.21.1-linux_musl_aarch64.tar.gz
    tar xzf "$TOOLS/zulu.tar.gz" -C "$TOOLS"
    d=$(ls -d "$TOOLS"/zulu* | grep -v '\.tar\.gz$' | head -1)
    chmod -R +x "$d/bin" "$d/lib"
    basename "$d" > "$TOOLS/jdk.path"
  fi
  JAVA="$TOOLS/$(cat "$TOOLS/jdk.path")/bin/java"
fi
STDLIB="$LIB/kotlin-stdlib-2.1.20.jar"
# 只给测试用（runBlocking 驱动 suspend 用例）；主代码不依赖
COROUTINES="$LIB/kotlinx-coroutines-core-jvm-1.8.0.jar"
RHINO="$LIB/rhino-1.7.15.jar"
COMPILER_CP=$(ls "$LIB"/*.jar | tr '\n' ':')

OUT="$ROOT/build/classes"
rm -rf "$OUT"
mkdir -p "$OUT"

echo "== 编译"
"$JAVA" -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -nowarn -jvm-target 17 \
  -classpath "$STDLIB:$RHINO:$COROUTINES" \
  -d "$OUT" \
  $(find "$ROOT/src" -name '*.kt')

echo "== 自测：核心契约"
"$JAVA" -cp "$OUT:$STDLIB:$COROUTINES" mychat.framework.SelfTestKt

echo "== 自测：Rhino 沙箱"
"$JAVA" -cp "$OUT:$STDLIB:$RHINO" mychat.framework.sandbox.RhinoSelfTestKt

echo "== 自测：模块树与 assets 同步"
"$JAVA" -Dmychat.repoRoot="$ROOT/.." -cp "$OUT:$STDLIB" mychat.framework.ModuleTreeTestKt

echo "== 自测：ModuleKit 与真模块集成"
"$JAVA" -Dmychat.repoRoot="$ROOT/.." -cp "$OUT:$STDLIB:$RHINO" mychat.framework.sandbox.ModuleKitIntegrationTestKt

echo "== 自测：HookRunner"
"$JAVA" -cp "$OUT:$STDLIB" mychat.framework.HookRunnerTestKt

echo "== 自测：Directive 协议"
"$JAVA" -cp "$OUT:$STDLIB" mychat.framework.DirectiveTestKt

echo "== 自测：用户输入转义"
"$JAVA" -cp "$OUT:$STDLIB" mychat.framework.EscapeTestKt

echo "== 自测：向量与分词"
"$JAVA" -Dmychat.repoRoot="$ROOT/.." -cp "$OUT:$STDLIB" mychat.framework.EmbeddingTestKt

echo "== 自测：管家"
"$JAVA" -cp "$OUT:$STDLIB" mychat.framework.ButlerTestKt

echo "== 自测：端到端链路"
"$JAVA" -Dmychat.repoRoot="$ROOT/.." -cp "$OUT:$STDLIB" mychat.framework.PipelineTestKt

echo "== 自测：注入块预览与快照"
"$JAVA" -Dmychat.repoRoot="$ROOT/.." -cp "$OUT:$STDLIB" mychat.framework.InjectionPreviewTestKt

echo "== 自测：组装链路"
"$JAVA" -Dmychat.repoRoot="$ROOT/.." -cp "$OUT:$STDLIB:$RHINO" mychat.framework.sandbox.EndToEndTestKt

echo "== 自测：执行引擎注册表"
"$JAVA" -cp "$OUT:$STDLIB:$RHINO" mychat.framework.sandbox.ExecutorTestKt

echo "== 自测：标签记忆"
"$JAVA" -cp "$OUT:$STDLIB" mychat.framework.MemoryTestKt

echo "== 自测：压力（1000 标签 / 100 轮）"
"$JAVA" -cp "$OUT:$STDLIB:$COROUTINES" mychat.framework.StressTestKt
