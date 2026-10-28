package com.jacobusoettle.satmap.offline

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.jacobusoettle.satmap.MainActivity

/** "Save to a folder" in the share chooser on API 26–33 (`EXTRA_INITIAL_INTENTS`; API 34+ uses a chooser custom
 *  action): forwards to `MainActivity`, which picks the folder and copies the files. */
class SaveToFolderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(BackupSharing.ACTION_SAVE_TO_FOLDER)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
