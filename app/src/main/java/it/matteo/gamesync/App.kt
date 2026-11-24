package it.matteo.gamesync

import android.app.Application

class App : Application() {
    override fun onCreate() { super.onCreate(); Store.init(this); Bridge.init(this) }
}
