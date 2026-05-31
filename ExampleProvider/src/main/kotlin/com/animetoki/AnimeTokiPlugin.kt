package com.animetoki

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugin.Plugin
import com.lagradost.cloudstream3.plugin.CloudstreamPlugin

@CloudstreamPlugin
class AnimeTokiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeTokiProvider())
    }
}
