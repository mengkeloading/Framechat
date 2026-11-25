package com.simplechat.app

import org.junit.Ignore
import org.junit.Test

/**
 * 其余断言的骨架。
 * 每条方法名与 chat-demo-main 的断言一一对应，接好线后去掉 @Ignore。
 *
 * 依赖 Android 的部分（Room / ChatViewModel / assets）需要
 * Robolectric 或 instrumented test，暂不在此实现。
 */
@Ignore("待接入 ChatViewModel / Room / ModuleRegistry")
class RemainingAssertionsTest {

    // ---- 框架核心（chat-demo-main 用例 1-14）----

    @Test fun plainMessagePersistsTwoRows() {}
    @Test fun assistantRowContentMatchesReply() {}
    @Test fun stateReturnsToIdle() {}
    @Test fun extraInjectedIntoRequest() {}
    @Test fun userMessageNotDuplicated() {}
    @Test fun taskRewriteProducesFixedText() {}
    @Test fun localPersistsWithoutNetwork() {}
    @Test fun invokeChainApplies() {}
    @Test fun moduleExceptionDoesNotBlock() {}
    @Test fun softModuleSingleSystemMessage() {}
    @Test fun toolCallFragmentsAccumulate() {}
    @Test fun toolRecordInsertedThenUpdated() {}
    @Test fun toolMessageAppearsOnceInRoundTwo() {}
    @Test fun assistantToolRowStaysInContext() {}
    @Test fun roundTwoUserNotDuplicated() {}
    @Test fun emptyArgsFallbackToBrace() {}
    @Test fun maxToolRoundsProducesSystemNotice() {}
    @Test fun stateIdleAfterTruncation() {}
    @Test fun errorNotOverwrittenByFinally() {}
    @Test fun errorMessageClassified() {}

    // ---- L1 能力模型 ----

    @Test fun registryLoadsAllModules() {}
    @Test fun frontMatterParsed() {}
    @Test fun referencesNotInjectedIntoSystem() {}
    @Test fun resourceReadFromReference() {}
    @Test fun rewriteUsesResourceContent() {}
    @Test fun quotaExceededThrows() {}
    @Test fun undeclaredCapabilityThrows() {}
    @Test fun sandboxHasNoAmbientAuthority() {}
    @Test fun auditContainsSuccessAndFailure() {}

    // ---- 多会话隔离 ----

    @Test fun sessionsDoNotLeakMessages() {}
    @Test fun contextQueryScopedBySession() {}
    @Test fun requestBodyContainsOnlyOwnHistory() {}
    @Test fun budgetIsPerSession() {}
    @Test fun switchingClearsErrorState() {}

    // ---- 模块库扩充 ----

    @Test fun threeNewPackagesDiscovered() {}
    @Test fun codeTagAddsTextFence() {}
    @Test fun longFoldAppendsNotice() {}
    @Test fun mathGuardInjectsExtra() {}

    // ---- 日常可用 ----

    @Test fun persistRoundTrip() {}
    @Test fun sessionDeleteCascades() {}
    @Test fun exportMarkdownContainsContent() {}
    @Test fun cancelKeepsPartialText() {}
    @Test fun retryDoesNotDuplicateUser() {}
    @Test fun autoTitleFromFirstMessage() {}
}
