package com.github.reygnn.kolibri_launcher

import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Eine leere Activity, die mit @AndroidEntryPoint annotiert ist.
 * Sie wird NUR für instrumentierte Tests verwendet, um Hilt-Fragmente
 * zu hosten, die eine Hilt-fähige Activity benötigen.
 *
 * Sie wird im src/debug-Ordner platziert, damit sie nicht in deiner
 * finalen Release-App enthalten ist.
 */
@AndroidEntryPoint
class HiltTestActivity : AppCompatActivity()