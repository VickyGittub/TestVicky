package com.animetoki

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeTokiPlugin : Plugin() {  // Add empty parentheses
    override fun load(context: Context) {
        registerMainAPI(AnimeTokiProvider())
    }
}
