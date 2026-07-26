package com.subtitleedit

import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorConfigurationRecreationTest {
    @Test
    fun recreateRestoresUnsavedListDocument() {
        ActivityScenario.launch(EditorActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[EditorViewModel::class.java]
                model.documentLoaded = true
                model.currentFormat = SubtitleParser.SubtitleFormat.SRT
                model.subtitleEntries = mutableListOf(
                    SubtitleEntry(1, 0L, 1_000L, "未保存内容")
                )
                model.hasUnsavedChanges = true
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[EditorViewModel::class.java]
                val list = activity.findViewById<RecyclerView>(R.id.rvSubtitles)
                assertTrue(model.hasUnsavedChanges)
                assertEquals("未保存内容", model.subtitleEntries.single().text)
                assertEquals(1, list.adapter?.itemCount)
                assertEquals(View.VISIBLE, list.visibility)
            }
        }
    }
}
