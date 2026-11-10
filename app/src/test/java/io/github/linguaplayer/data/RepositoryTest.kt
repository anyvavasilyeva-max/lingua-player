package io.github.linguaplayer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.linguaplayer.data.db.EntryFilter
import io.github.linguaplayer.data.db.EntrySort
import io.github.linguaplayer.data.db.EntryType
import io.github.linguaplayer.data.db.PlaylistKind
import io.github.linguaplayer.data.db.TagEntity
import io.github.linguaplayer.data.db.TagMatchMode
import io.github.linguaplayer.data.db.TypeFilter
import io.github.linguaplayer.data.repo.EntryDraft
import io.github.linguaplayer.data.repo.SmartRules
import io.github.linguaplayer.data.repo.UserFacingException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var t: TestDb
    private var pairId = 0L

    @Before
    fun setUp() = runTest {
        t = TestDb()
        pairId = t.pairs.getAll().single().id
    }

    @After
    fun tearDown() = t.close()

    private suspend fun add(source: String, translation: String, type: EntryType = EntryType.WORD, tags: Set<Long> = emptySet()) =
        t.entries.save(EntryDraft(pairId = pairId, source = source, translation = translation, type = type, tagIds = tags))

    private suspend fun sources(filter: EntryFilter) = t.entries.getEntries(filter).map { it.source }

    @Test
    fun seedsKoreanRussianPairAndSystemTag() = runTest {
        val pair = t.pairs.getAll().single()
        assertEquals("ko-KR", pair.sourceLang)
        assertEquals("ru-RU", pair.targetLang)
        val tag = t.tags.dontRememberTag()
        assertNotNull(tag)
        assertEquals("Не помню", tag!!.name)
    }

    @Test
    fun dontRememberToggleSurvivesRename() = runTest {
        val id = add("포크", "Вилка")
        val sys = t.tags.dontRememberTag()!!
        t.tags.rename(sys.id, "Сложно запомнить")
        assertTrue(t.entries.toggleDontRemember(id))
        assertEquals(setOf(sys.id), t.entries.getDetails(id)!!.tagIds)
        assertFalse(t.entries.toggleDontRemember(id))
        assertTrue(t.entries.getDetails(id)!!.tagIds.isEmpty())
        assertEquals(TagEntity.SYSTEM_DONT_REMEMBER, t.tags.dontRememberTag()!!.systemKey)
    }

    @Test
    fun systemTagCannotBeDeleted() = runTest {
        val sys = t.tags.dontRememberTag()!!
        try {
            t.tags.delete(sys.id)
            fail("expected exception")
        } catch (e: UserFacingException) {
            assertEquals(UserFacingException.Reason.SYSTEM_TAG, e.reason)
        }
        assertNotNull(t.tags.dontRememberTag())
    }

    @Test
    fun tagNamesAreUniqueIgnoringCaseIncludingCyrillic() = runTest {
        t.tags.create("Кухня")
        try {
            t.tags.create("  кухня ")
            fail("expected exception")
        } catch (e: UserFacingException) {
            assertEquals(UserFacingException.Reason.TAG_EXISTS, e.reason)
        }
        assertEquals(t.tags.getOrCreate("КУХНЯ")!!.name, "Кухня")
    }

    @Test
    fun deletingTagKeepsEntries() = runTest {
        val tag = t.tags.create("быт")
        val id = add("설거지를 하다", "Мыть посуду", EntryType.PHRASE, setOf(tag.id))
        t.tags.delete(tag.id)
        val details = t.entries.getDetails(id)!!
        assertTrue(details.tagIds.isEmpty())
        assertEquals("설거지를 하다", details.entry.source)
    }

    @Test
    fun updatingTagsReplacesTagSet() = runTest {
        val a = t.tags.create("A")
        val b = t.tags.create("B")
        val c = t.tags.create("C")
        val id = add("포크", "Вилка", tags = setOf(a.id, b.id))
        t.entries.save(EntryDraft(id, pairId, "포크", "Вилка", EntryType.WORD, setOf(b.id, c.id)))
        assertEquals(setOf(b.id, c.id), t.entries.getDetails(id)!!.tagIds)
        t.entries.addTags(listOf(id), listOf(a.id))
        assertEquals(setOf(a.id, b.id, c.id), t.entries.getDetails(id)!!.tagIds)
    }

    @Test
    fun filtersByMultipleTagsAnyAndAll() = runTest {
        val kitchen = t.tags.create("кухня").id
        val topik = t.tags.create("TOPIK 3급").id
        add("포크", "Вилка", tags = setOf(kitchen))
        add("숟가락", "Ложка", tags = setOf(kitchen, topik))
        add("학교", "Школа", tags = setOf(topik))
        add("물", "Вода")

        val any = sources(EntryFilter(tagIds = setOf(kitchen, topik), tagMode = TagMatchMode.ANY))
        assertEquals(listOf("숟가락", "포크", "학교"), any)
        val all = sources(EntryFilter(tagIds = setOf(kitchen, topik), tagMode = TagMatchMode.ALL))
        assertEquals(listOf("숟가락"), all)
    }

    @Test
    fun filtersByTypeAndText() = runTest {
        add("포크", "Вилка")
        add("설거지를 하다", "Мыть посуду", EntryType.PHRASE)
        add("어떻게 해야 할까요?", "Что мне следует сделать?", EntryType.PHRASE)

        assertEquals(listOf("포크"), sources(EntryFilter(type = TypeFilter.WORDS)))
        assertEquals(2, sources(EntryFilter(type = TypeFilter.PHRASES)).size)
        // Поиск без учёта регистра по кириллице и по корейскому.
        assertEquals(listOf("포크"), sources(EntryFilter(text = "ВИЛ")))
        assertEquals(listOf("설거지를 하다"), sources(EntryFilter(text = "설거지")))
        // Спецсимволы LIKE не мешают поиску.
        assertEquals(listOf("어떻게 해야 할까요?"), sources(EntryFilter(text = "?")))
    }

    @Test
    fun entryCanBeInSeveralPlaylistsAndDeletingPlaylistKeepsEntries() = runTest {
        val e1 = add("포크", "Вилка")
        val e2 = add("숟가락", "Ложка")
        val p1 = t.playlists.create("Кухня", pairId)
        val p2 = t.playlists.create("Повторить", pairId)
        t.playlists.addEntries(p1, listOf(e1, e2))
        t.playlists.addEntries(p2, listOf(e1))
        assertEquals(setOf(p1, p2), t.entries.getDetails(e1)!!.playlistIds)

        t.playlists.delete(p1)
        assertEquals(2, t.entries.getEntries(EntryFilter()).size)
        assertEquals(setOf(p2), t.entries.getDetails(e1)!!.playlistIds)
    }

    @Test
    fun manualOrderInPlaylist() = runTest {
        val ids = listOf(add("가", "a"), add("나", "b"), add("다", "c"))
        val p = t.playlists.create("Порядок", pairId)
        t.playlists.addEntries(p, ids.reversed())
        val filter = EntryFilter(playlistId = p, sort = EntrySort.PLAYLIST_ORDER)
        assertEquals(listOf("다", "나", "가"), sources(filter))
        t.playlists.move(p, from = 0, to = 2)
        assertEquals(listOf("나", "가", "다"), sources(filter))
        // Повторное добавление не создаёт дубликат.
        t.playlists.addEntries(p, ids)
        assertEquals(3, sources(filter).size)
    }

    @Test
    fun deletingEntryRemovesItFromPlaylists() = runTest {
        val e = add("포크", "Вилка")
        val p = t.playlists.create("Кухня", pairId)
        t.playlists.addEntries(p, listOf(e))
        t.entries.delete(listOf(e))
        assertTrue(sources(EntryFilter(playlistId = p)).isEmpty())
        assertNotNull(t.playlists.get(p))
    }

    @Test
    fun smartPlaylistFollowsRules() = runTest {
        val topik = t.tags.create("TOPIK 3급").id
        add("포크", "Вилка", tags = setOf(topik))
        add("설거지를 하다", "Мыть посуду", EntryType.PHRASE, setOf(topik))
        add("어떻게 해야 할까요?", "Что мне следует сделать?", EntryType.PHRASE)

        val id = t.playlists.create(
            "TOPIK 3급 — фразы",
            pairId,
            PlaylistKind.SMART,
            SmartRules(TypeFilter.PHRASES, setOf(topik), TagMatchMode.ANY),
        )
        val content = t.playlists.observeEntries(id).first().map { it.entry.source }
        assertEquals(listOf("설거지를 하다"), content)

        // Новая подходящая запись автоматически попадает в умный плейлист.
        add("요리를 하다", "Готовить", EntryType.PHRASE, setOf(topik))
        assertEquals(2, t.playlists.observeEntries(id).first().size)
    }

    @Test
    fun emptySourceIsRejected() = runTest {
        try {
            add("   ", "Вилка")
            fail("expected exception")
        } catch (e: UserFacingException) {
            assertEquals(UserFacingException.Reason.EMPTY_FIELD, e.reason)
        }
    }

    @Test
    fun languagePairsUseCanonicalBcp47() = runTest {
        val id = t.pairs.add("en_us", "ru-ru")
        val p = t.pairs.get(id)!!
        assertEquals("en-US", p.sourceLang)
        assertEquals("ru-RU", p.targetLang)
        try {
            t.pairs.add("en-US", "ru-RU")
            fail("expected exception")
        } catch (e: UserFacingException) {
            assertEquals(UserFacingException.Reason.PAIR_EXISTS, e.reason)
        }
        add("포크", "Вилка")
        try {
            t.pairs.delete(pairId)
            fail("expected exception")
        } catch (e: UserFacingException) {
            assertEquals(UserFacingException.Reason.PAIR_IN_USE, e.reason)
        }
    }

    @Test
    fun coverFilesAreDeletedWhenReplacedOrPlaylistDeleted() = runTest {
        val deleted = mutableListOf<String>()
        val repo = io.github.linguaplayer.data.repo.PlaylistRepository(t.db, t.entries, t.clock) { deleted += it }
        val id = repo.create("Обложка", pairId)
        repo.setCover(id, "/covers/a.jpg")
        assertEquals("/covers/a.jpg", repo.get(id)!!.coverPath)
        repo.setCover(id, "/covers/b.jpg")
        assertEquals(listOf("/covers/a.jpg"), deleted)
        repo.delete(id)
        assertEquals(listOf("/covers/a.jpg", "/covers/b.jpg"), deleted)
        val id2 = repo.create("Без обложки", pairId)
        repo.setCover(id2, "/covers/c.jpg")
        repo.setCover(id2, null)
        assertEquals("/covers/c.jpg", deleted.last())
        org.junit.Assert.assertNull(repo.get(id2)!!.coverPath)
    }

    @Test
    fun languagePairsDoNotShareLibrariesOrPlaylists() = runTest {
        val ko = pairId
        val en = t.pairs.add("en-US", "ru-RU")
        val shared = t.tags.create("кухня").id
        val fork = add("포크", "Вилка", tags = setOf(shared))
        val enFork = t.entries.save(EntryDraft(pairId = en, source = "fork", translation = "Вилка", type = EntryType.WORD, tagIds = setOf(shared)))
        t.entries.save(EntryDraft(pairId = en, source = "to wash dishes", translation = "Мыть посуду", type = EntryType.PHRASE, tagIds = emptySet()))

        val koList = t.playlists.create("Корейская кухня", ko)
        val enList = t.playlists.create("English kitchen", en)
        val enSmart = t.playlists.create("EN кухня", en, PlaylistKind.SMART, SmartRules(TypeFilter.ALL, setOf(shared), TagMatchMode.ANY))

        // Списки плейлистов — только своей пары.
        assertEquals(listOf("Корейская кухня"), t.playlists.observeAll(ko).first().map { it.playlist.name })
        assertEquals(setOf("English kitchen", "EN кухня"), t.playlists.observeAll(en).first().map { it.playlist.name }.toSet())

        // Запись другой пары в плейлист не добавляется.
        t.playlists.addEntries(koList, listOf(fork, enFork))
        assertEquals(listOf("포크"), t.playlists.observeEntries(koList).first().map { it.entry.source })
        assertEquals(1, t.playlists.observeAll(ko).first().single().entryCount)

        // Редактор записи не может привязать её к плейлисту чужой пары.
        t.entries.save(EntryDraft(enFork, en, "fork", "Вилка", EntryType.WORD, setOf(shared), playlistIds = setOf(koList, enList)))
        assertEquals(setOf(enList), t.entries.getDetails(enFork)!!.playlistIds)

        // Умный плейлист с общим тегом берёт записи только своей пары.
        assertEquals(listOf("fork"), t.playlists.observeEntries(enSmart).first().map { it.entry.source })

        // Библиотека пары.
        assertEquals(listOf("포크"), t.entries.getEntries(EntryFilter(pairId = ko)).map { it.source })
        assertEquals(2, t.entries.getEntries(EntryFilter(pairId = en)).size)

        // Фильтры тегов: в паре видны только используемые в ней (+ системный «Не помню»).
        val onlyEnTag = t.tags.create("only-en").id
        t.entries.addTags(listOf(enFork), listOf(onlyEnTag))
        val koTags = t.tags.observeUsedInPair(ko).first().map { it.name }
        assertEquals(listOf("Не помню", "кухня"), koTags)
        assertTrue("only-en" in t.tags.observeUsedInPair(en).first().map { it.name })
    }

    @Test
    fun pairWithPlaylistsCannotBeDeleted() = runTest {
        val en = t.pairs.add("en-US", "ru-RU")
        t.playlists.create("Пустой", en)
        try {
            t.pairs.delete(en)
            fail("expected exception")
        } catch (e: UserFacingException) {
            assertEquals(UserFacingException.Reason.PAIR_IN_USE, e.reason)
        }
    }
}
