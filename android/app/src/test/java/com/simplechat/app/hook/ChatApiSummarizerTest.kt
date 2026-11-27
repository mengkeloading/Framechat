package com.simplechat.app.hook

import com.simplechat.app.net.StreamEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 摘要把流读完后拼成一段；任何错误事件都算失败。 */
class ChatApiSummarizerTest {

    @Test
    fun concatenatesContentChunks() = runTest {
        val summarizer = ChatApiSummarizer { _ ->
            flowOf(
                StreamEvent.Content("技术栈 "),
                StreamEvent.Reasoning("（这段不该进摘要）"),
                StreamEvent.Content("Kotlin"),
                StreamEvent.Done,
            )
        }

        assertEquals("技术栈 Kotlin", summarizer.summarize("压一下"))
    }

    @Test
    fun errorEventYieldsNull() = runTest {
        val summarizer = ChatApiSummarizer { _ ->
            flowOf(
                StreamEvent.Content("写了一半"),
                StreamEvent.Error("boom", code = 500),
            )
        }

        assertNull(summarizer.summarize("压一下"))
    }
}
