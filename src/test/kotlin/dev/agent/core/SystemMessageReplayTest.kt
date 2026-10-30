package dev.agent.core

import dev.agent.core.api.AgentFactory
import dev.agent.core.bean.AgentMessage
import dev.agent.core.bean.SystemMessage
import dev.agent.core.bean.TextContent
import dev.agent.core.bean.UserMessage
import dev.agent.core.protocol.ProtocolCodec
import dev.agent.core.runtime.Transcript
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 系统正文块从 Bean setter 到回放和 reset 均保留正文、签名及原有合并规则。 */
class SystemMessageReplayTest : CoreTestSupport() {
    @Test fun beanSetterBlocksOverrideStalePlainContentAndKeepSignatures() {
        val signed = TextContent("signed policy", "signature-1")
        val system = SystemMessage()
        system.content = "stale policy"
        system.contentBlocks = listOf(signed, TextContent("second line"))

        val transcript = Transcript()
        val replay = assertNotNull(transcript.currentSystemMessage(listOf(system)))
        assertEquals("signed policy\nsecond line", replay.content)
        assertEquals("signed policy\nsecond line", transcript.currentSystemPrompt(listOf(system)))
        val blocks = assertNotNull(replay.contentBlocks)
        assertEquals("signature-1", blocks.first().textSignature)
        val wire = ProtocolCodec().message(replay)
        assertEquals(JsonPrimitive("signature-1"), wire["content"]!!.jsonArray.first().jsonObject["textSignature"])
        blocks.first().text = "changed replay"
        assertEquals("signed policy", signed.text)
    }

    @Test fun blockConstructorHasTheSameReplayBehaviorAsBeanSetter() {
        val system = SystemMessage(listOf(TextContent("first", "signature"), TextContent("second")))
        val replay = assertNotNull(Transcript().currentSystemMessage(listOf(system)))
        assertEquals("first\nsecond", replay.content)
        assertEquals("signature", assertNotNull(replay.contentBlocks).first().textSignature)
        assertEquals(ProtocolCodec().message(system), ProtocolCodec().message(replay))
    }

    @Test fun mergedSystemsKeepTextSeparatorsSectionsToolsAndSignatures() {
        val first = SystemMessage("plain baseline")
        first.timestamp = 123
        first.sections = mapOf("memory" to "old", "policy" to "keep")
        first.toolsAdded = listOf(definition("old-tool").declaration())
        val second = SystemMessage()
        second.content = "stale"
        second.contentBlocks = listOf(TextContent("signed update", "update-signature"), TextContent("next line"))
        second.sections = mapOf("memory" to "new", "policy" to null)
        second.toolsRemoved = listOf("old-tool")
        second.toolsAdded = listOf(definition("new-tool").declaration())
        val sectionsOnly = SystemMessage()
        sectionsOnly.sections = mapOf("extra" to "retained")
        val last = SystemMessage("last update")
        val history = listOf(first, UserMessage("hello"), second, sectionsOnly, last)

        val transcript = Transcript()
        val replay = assertNotNull(transcript.currentSystemMessage(history))
        assertEquals("plain baseline\n\nsigned update\nnext line\n\nlast update", replay.content)
        assertEquals(123L, replay.timestamp)
        assertEquals(mapOf("memory" to "new", "extra" to "retained"), replay.sections)
        assertEquals(listOf("new-tool"), replay.toolsAdded.map { it.name })
        val blocks = assertNotNull(replay.contentBlocks)
        assertEquals(listOf("plain baseline", "", "signed update", "next line", "", "last update"), blocks.map { it.text })
        assertEquals("update-signature", blocks[2].textSignature)
        assertEquals(replay.content, blocks.joinToString("\n") { it.text })
        assertEquals(ProtocolCodec().message(replay), ProtocolCodec().message(assertNotNull(transcript.currentSystemMessage(listOf(replay)))))
        assertEquals(replay.content + "\n\nnew\n\nretained", transcript.currentSystemPrompt(history))
    }

    @Test fun explicitlyEmptyBlocksSuppressStalePlainContent() {
        val empty = SystemMessage("stale policy")
        empty.contentBlocks = emptyList()
        val transcript = Transcript()
        val replay = assertNotNull(transcript.currentSystemMessage(listOf(empty)))
        assertEquals("", replay.content)
        assertEquals(emptyList(), replay.contentBlocks)
        assertEquals("", transcript.currentSystemPrompt(listOf(empty)))
        assertEquals("effective", transcript.currentSystemPrompt(listOf(empty, SystemMessage("effective"))))
    }

    @Test fun resetKeepsBeanSetterBodyAndSignaturesAcrossRepeatedReplay() {
        val system = SystemMessage()
        system.contentBlocks = listOf(TextContent("signed policy", "saved-signature"))
        system.sections = mapOf("memory" to "retained")
        val configuration = config(ScriptedModelClient(reply()))
        configuration.messages = listOf<AgentMessage>(system, UserMessage("old conversation"), SystemMessage("later instruction"))
        val agent = AgentFactory().create(configuration)
        try {
            val expectedPrompt = "signed policy\n\nlater instruction\n\nretained"
            assertEquals(expectedPrompt, agent.getState().systemPrompt)
            agent.reset()
            val firstReset = agent.getState()
            val baseline = firstReset.messages.single() as SystemMessage
            assertEquals(expectedPrompt, firstReset.systemPrompt)
            assertEquals("saved-signature", assertNotNull(baseline.contentBlocks).first().textSignature)
            assertTrue(firstReset.messages.none { it is UserMessage })
            val encoded = ProtocolCodec().message(baseline)
            agent.reset()
            val secondReset = agent.getState()
            assertEquals(expectedPrompt, secondReset.systemPrompt)
            assertEquals(encoded, ProtocolCodec().message(secondReset.messages.single()))
        } finally {
            agent.close()
        }
    }
}
