package com.cj.tapblok.database

/**
 * What tapping the NFC tag (or scanning the QR code) does to an app that is locked on its
 * session budget.
 *
 * Neither mode can clear a daily-cap lock — only the daily rollover does that.
 */
enum class TagUnlockMode {
    /**
     * The tag does exactly what waiting out the reset timer does: the session counter goes
     * to zero and a full fresh budget is available. The tag is a shortcut past the wait,
     * and the walk to wherever it lives is the intended friction.
     */
    SKIP_THE_WAIT,

    /**
     * The tag grants a fixed window of access, after which the app re-locks because the
     * session counter was never cleared. Keeps the session budget meaningful and makes the
     * tag an escape hatch rather than a refill.
     */
    GRACE_WINDOW
}
