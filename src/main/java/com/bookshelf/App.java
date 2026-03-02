package com.bookshelf;

import java.nio.file.Files;
import java.nio.file.Path;

public class App {

    public static void main(String[] args) throws Exception {
        Path staticDir = Path.of("static");
        Files.createDirectories(staticDir);

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.runMigrations();

        BookRepository repository = new JdbcBookRepository(
            dbConfig.getDataSource()
        );
        ShelfRepository shelfRepository = new JdbcShelfRepository(
            dbConfig.getDataSource()
        );
        OpenLibraryService openLibraryService = new OpenLibraryService(
            repository
        );
        BookController controller = new BookController(
            repository,
            openLibraryService,
            shelfRepository
        );
        ShelfController shelfController = new ShelfController(
            shelfRepository,
            repository
        );
        Router router = createRouter(controller, shelfController);

        StaticFileHandler staticHandler = new StaticFileHandler(staticDir);
        router.setFallbackHandler(staticHandler::handle);

        String portEnv = System.getenv("APP_PORT");
        int port = 8080;
        if (portEnv != null) {
            try {
                port = Integer.parseInt(portEnv);
            } catch (NumberFormatException e) {
                System.err.println(
                    "Warning: Invalid APP_PORT '" +
                        portEnv +
                        "', defaulting to 8080"
                );
            }
        }
        HttpServer server = new HttpServer(port, router);
        server.start();
        System.out.println("Bookshelf server started on port " + port);

        Runtime.getRuntime().addShutdownHook(
            new Thread(() -> {
                try {
                    openLibraryService.shutdown();
                } catch (Exception e) {
                    System.err.println("Shutdown error: " + e.getMessage());
                }
                try {
                    server.stop();
                } catch (Exception e) {
                    System.err.println("Shutdown error: " + e.getMessage());
                }
                try {
                    dbConfig.close();
                } catch (Exception e) {
                    System.err.println("Shutdown error: " + e.getMessage());
                }
            })
        );

        // Block main thread to keep JVM alive (accept thread is daemon)
        Thread.currentThread().join();
    }

    public static Router createRouter(
        BookController controller,
        ShelfController shelfController
    ) {
        Router router = new Router();
        router.addRoute("GET", "/books", controller::handleGetBooks);
        router.addRoute("POST", "/books", controller::handleCreateBook);
        router.addRoute("POST", "/books/re-enrich", controller::handleReEnrich);
        router.addRoute("GET", "/books/{id}", controller::handleGetBook);
        router.addRoute("PUT", "/books/{id}", controller::handleUpdateBook);
        router.addRoute("DELETE", "/books/{id}", controller::handleDeleteBook);
        router.addRoute(
            "GET",
            "/books/isbn/{isbn}",
            controller::handleGetBookByIsbn
        );
        router.addRoute("GET", "/books/{id}/cover", controller::handleGetCover);

        // Shelf routes — static segments registered before {id} params
        router.addRoute("GET", "/shelves", shelfController::handleGetShelves);
        router.addRoute("POST", "/shelves", shelfController::handleCreateShelf);
        router.addRoute(
            "PUT",
            "/shelves/reorder",
            shelfController::handleReorderShelves
        );
        router.addRoute(
            "GET",
            "/shelves/{id}",
            shelfController::handleGetShelf
        );
        router.addRoute(
            "PUT",
            "/shelves/{id}",
            shelfController::handleUpdateShelf
        );
        router.addRoute(
            "DELETE",
            "/shelves/{id}",
            shelfController::handleDeleteShelf
        );
        router.addRoute(
            "POST",
            "/shelves/{id}/books",
            shelfController::handleAddBook
        );
        router.addRoute(
            "PUT",
            "/shelves/{id}/books/reorder",
            shelfController::handleReorderBooks
        );
        router.addRoute(
            "DELETE",
            "/shelves/{id}/books/{bookId}",
            shelfController::handleRemoveBook
        );

        return router;
    }
}
