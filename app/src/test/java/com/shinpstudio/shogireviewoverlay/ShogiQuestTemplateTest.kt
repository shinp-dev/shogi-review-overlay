package com.shinpstudio.shogireviewoverlay

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class ShogiQuestTemplateTest {
    @Test fun questTemplatesContainAllSixteenSideSpecificPieces() {
        val content = File("src/main/assets/shogi_quest_templates_v1.txt").readText()
        val classifier = PieceTemplateModel.create(content)
        val entries = content.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        assertEquals(16, entries.size)
        assertEquals("PLNSGBRKplnsgbrk".toSet(), entries.map { it[0] }.toSet())
        for (line in entries) {
            val actual = classifier.match(decode(line.substring(2)))
            assertEquals("Template ${line[0]} fails self-match", line[0], actual.code)
            assertTrue(actual.score >= 0.99)
        }
        assertNull(classifier.match(PieceTemplateModel.fromMask(BooleanArray(1024))).code)
    }

    @Test fun otherSquaresFromQuestScreenshotRecognizeWithoutExactTemplateMatch() {
        val content = File("src/main/assets/shogi_quest_templates_v1.txt").readText()
        val model = PieceTemplateModel.create(content)
        val heldOut = mapOf(
            'p' to "AAAAACAAAAQgAAAEAAAABAAAAQQQAAeEEAA/hBAA8QQAAeAIAAOACAgPhAgI384ICP3sAAB5mBAADY+QAf//0AX/2BAEAZwQBA+cIAB9nCACM4AgAgfAIAID4CACAABAAAAAQAAAAEABAABAAYABgAHwD4AA/n+AAB/8AAAD4AA=",
            's' to "AAAAAAAAAAAAAAAAAAIAAAAHAMEADwPhBh8P4T+nfgGPw20Ag8MvgIB3bIIAf+wCAc8fggOH/BpD/nxgQP4exEDGf4QAzgcEAf7GBAHG7gAjz3wII/w8CCHgPAgAAAgYAAAAEBAAABAQAAAQGAAAIB8AA+AP4A/gAP//AAAf8AA=",
            'P' to "AAwIAAAgAgABgADAAgAAAAIAACACAAAgAgAAIAAAgCAAA+AgAAHgAAA5zwAEHf8ABB3wAAQd35AEP//QBf/AEABt3hAAHc+QADvfgAAx+IAIAPAACAHACAiHgAgI3gAIAPwACAHwAAgAAAAAAAAAAArv//gP///4B///8AAAAAA=",
            'L' to "ABgIAABAAwACAAAgAgAAIAAAACAAAAAgBAAAIAQA4AAEA/AABAPAEAQfABAAO44QAAP/kAA//xAJ/4AACA/8AAg7j+AIc/noAP/8CAOc3AgQD/gIEA84ABAP+AAQD/AEAAAABAAAAAQAAAAEIAAABCAAAAA////8H///+AAAAAA=",
            'G' to "AEADAAMAAGAEAAAQAAAAEAAAABAAAwAACAfAAAgDwAgAB4AIAAfAABAOYAAQHDgAEBgcBAA4fwQAf/fEIc+D4CMDAAIgA/gCAB/4AgA/OABAAzwAQEM8AAB7eAEAO8ABAAP/AIM//4CB//+AgOAAAAAAAAAAAAAA//////////8=",
            'N' to "ABAMAAEAAEAAAAAgAAAAIAAAAAAAAAAABAAAEARwMBAAODgQADgYEAAwGAAIMBsACD4/gAh8+AgD8B+IADj/gAB8uAAAdBgAEPAfBBGw/wQTMBAEBDAb4ACz//AA88BwIHAAAiBwAAIgAAACAAAAAgAAAABAAAAAP////h////w="
        )
        heldOut.forEach { (expected, packed) ->
            val result = model.match(decode(packed))
            assertEquals("Held-out Quest ${expected} failed", expected, result.code)
            assertTrue("Low score for ${expected}", result.score >= 0.74)
        }
    }

    private fun decode(packed: String): PieceTemplateModel.Signature {
        val data = Base64.getDecoder().decode(packed)
        assertEquals(128, data.size)
        val mask = BooleanArray(1024) { i ->
            data[i / 8].toInt() and 0xFF and (1 shl (7 - (i % 8))) != 0
        }
        return PieceTemplateModel.fromMask(mask)
    }
}
