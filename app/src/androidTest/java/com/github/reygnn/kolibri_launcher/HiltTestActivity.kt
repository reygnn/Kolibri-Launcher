package com.github.reygnn.kolibri_launcher

import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * A simple, empty activity used as a host for Fragments in Hilt UI tests.
 * The @AndroidEntryPoint annotation is crucial.
 */
@AndroidEntryPoint
class HiltTestActivity : AppCompatActivity()