package com.jacobusoettle.satmap.offline

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.jacobusoettle.satmap.MainActivity
import com.jacobusoettle.satmap.ui.SharePresenter

/** "Save to a folder" in the share chooser (`EXTRA_INITIAL_INTENTS` on API 26–33, the target of the chooser's custom
 *  action from API 34): tells `SharePresenter` at once, before the chooser's result reaches `MainActivity`, so the
 *  share (and the export's hold on downloads) lasts until the folder picker closes; then forwards to `MainActivity`,
 *  which picks the folder and copies the files. */
class SaveToFolderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SharePresenter.requestFolder()
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(BackupSharing.ACTION_SAVE_TO_FOLDER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
