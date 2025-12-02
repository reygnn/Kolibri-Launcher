package com.github.reygnn.kolibri_launcher.data

import com.github.reygnn.kolibri_launcher.domain.repository.FavoritesRepository
import com.github.reygnn.kolibri_launcher.fakes.FakeFavoritesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DER VERTRAG (CONTRACT)
 *
 * Diese Tests definieren das "Gesetz", an das sich JEDES FavoritesRepository halten muss.
 * Egal ob Fake, Room-Datenbank oder Cloud-Speicher.
 */
abstract class FavoritesRepositoryContractTest {

    // Die Factory-Methode: Die Unterklasse muss das zu testende Objekt liefern.
    abstract fun createRepository(): FavoritesRepository

    // --- DIE TESTS (Definieren das Verhalten) ---

    @Test
    fun `addFavorite - adds item to flow`() = runTest {
        val repo = createRepository()

        // Act
        val success = repo.addFavoriteComponent("com.example/Main")
        val currentFavorites = repo.favoriteComponentsFlow.first()

        // Assert
        assertTrue("Add should return true", success)
        assertTrue("Flow should contain added item", currentFavorites.contains("com.example/Main"))
    }

    @Test
    fun `removeFavorite - removes item from flow`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent("com.example/Main") // Setup state

        // Act
        val success = repo.removeFavoriteComponent("com.example/Main")
        val currentFavorites = repo.favoriteComponentsFlow.first()

        // Assert
        assertTrue("Remove should return true", success)
        assertFalse("Flow should not contain item anymore", currentFavorites.contains("com.example/Main"))
    }

    @Test
    fun `toggleFavorite - adds if not present`() = runTest {
        val repo = createRepository()

        repo.toggleFavoriteComponent("com.example/Toggle")

        assertTrue(repo.favoriteComponentsFlow.first().contains("com.example/Toggle"))
    }

    @Test
    fun `toggleFavorite - removes if present`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent("com.example/Toggle")

        repo.toggleFavoriteComponent("com.example/Toggle")

        assertFalse(repo.favoriteComponentsFlow.first().contains("com.example/Toggle"))
    }

    @Test
    fun `isFavorite - returns true for existing favorite`() = runTest {
        val repo = createRepository()
        repo.addFavoriteComponent("com.check/Me")

        assertTrue(repo.isFavoriteComponent("com.check/Me"))
    }
}

/**
 * IMPLEMENTIERUNG 1: Verifizierung des FAKES
 * (Läuft superschnell als Unit Test)
 */
class FakeFavoritesRepositoryTest : FavoritesRepositoryContractTest() {

    override fun createRepository(): FavoritesRepository {
        // Hier geben wir den Fake zurück.
        // Wenn dieser Test passed, erfüllt der Fake den Vertrag.
        return FakeFavoritesRepository()
    }
}

/**
 * IMPLEMENTIERUNG 2: Verifizierung des ECHTEN REPOS
 * (Würde im Ordner 'androidTest' liegen)
 */
/*
class RoomFavoritesRepositoryTest : FavoritesRepositoryContractTest() {

    // Setup für echte Datenbank (In-Memory Room)
    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    override fun createRepository(): FavoritesRepository {
        // Hier geben wir das ECHTE Repo zurück, das an der echten (in-memory) DB hängt.
        return RoomFavoritesRepository(db.favoritesDao())
    }
}
*/