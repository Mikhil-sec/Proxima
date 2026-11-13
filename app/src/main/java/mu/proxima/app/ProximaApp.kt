package mu.proxima.app

import android.app.Application

class ProximaApp : Application() {
    lateinit var graph: ProximaGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Simulation ON until every lane's real engine lands. Must be OFF for the final demo.
        val simulation = getSharedPreferences("proxima", MODE_PRIVATE).getBoolean("simulation", true)
        graph = ProximaGraph(this, simulation)
    }
}
