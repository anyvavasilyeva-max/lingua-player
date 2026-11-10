package io.github.linguaplayer

/** Application для тестов Robolectric (подключается через robolectric.properties). */
class TestLinguaApp : LinguaApp() {
    override val connectPlaybackService: Boolean get() = false
}
