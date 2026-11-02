package dev.jonas.openchat.core.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelListParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `openai style data array is parsed`() {
        val models = ModelListParser.parse(
            json,
            """
            {"object":"list","data":[
              {"id":"deepseek-v4.1-flash","name":"DeepSeek V4.1 Flash","context_length":128000},
              {"id":"glm-5.3"}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, models.size)
        assertEquals("DeepSeek V4.1 Flash", models[0].name)
        assertEquals(128000, models[0].contextWindow)
        assertEquals("glm-5.3", models[1].name)
    }

    @Test
    fun `architecture modalities detect vision`() {
        val models = ModelListParser.parse(
            json,
            """
            {"models":[
              {"id":"vision-model","architecture":{"input_modalities":["text","image"]}},
              {"id":"text-model","architecture":{"input_modalities":["text"]}}
            ]}
            """.trimIndent(),
        )
        assertTrue(models[0].supportsVision)
        assertTrue(!models[1].supportsVision)
    }

    @Test
    fun `opencode metadata exposes context vision and tools`() {
        val models = ModelListParser.parse(
            json,
            """
            {"object":"list","data":[
              {
                "id":"deepseek-v4.1-flash",
                "name":"DeepSeek V4.1 Flash",
                "limit":{"context":1000000,"output":384000},
                "modalities":{"input":["text","image/jpeg","image/png"],"output":["text"]},
                "capabilities":{"tools":{"supported":true},"reasoning":{"supported":true}},
                "api":{"chat-completions":{"base_url":"https://opencode.ai/inference/go/openai/v1"}}
              },
              {
                "id":"glm-5.3",
                "name":"GLM-5.3",
                "limit":{"context":1000000},
                "modalities":{"input":["text"]},
                "capabilities":{"tools":{"supported":true}}
              }
            ]}
            """.trimIndent(),
        )
        assertEquals(1_000_000, models[0].contextWindow)
        assertTrue(models[0].supportsVision)
        assertTrue(models[0].supportsTools)
        assertTrue(!models[1].supportsVision)
    }

    @Test
    fun `live opencode payload parses`() {
        val body = javaClass.classLoader!!
            .getResource("models-opencode.json")!!
            .readText()
        val models = ModelListParser.parse(json, body)
        assertEquals(28, models.size)
        val deepseek = models.first { it.id == "deepseek-v4.1-flash" }
        assertEquals(1_000_000, deepseek.contextWindow)
        assertTrue(deepseek.supportsVision)
        assertTrue(deepseek.supportsTools)
        val glm = models.first { it.id == "glm-5.3" }
        assertFalse(glm.supportsVision)
        assertTrue(glm.supportsTools)
    }

    @Test
    fun `duplicate ids are removed`() {
        val models = ModelListParser.parse(
            json,
            """{"data":[{"id":"a"},{"id":"a"}]}""",
        )
        assertEquals(1, models.size)
    }

    @Test
    fun `unknown payload returns empty list`() {
        assertTrue(ModelListParser.parse(json, "not json").isEmpty())
        assertTrue(ModelListParser.parse(json, """{"object":"list"}""").isEmpty())
        assertNull(ModelListParser.parse(json, """{"data":[]}""").firstOrNull())
    }
}
