package com.bookshelf;

import java.nio.file.Files;
import java.nio.file.Path;

public class App {

    public static void main(String[] args) throws Exception {
        Path staticDir = Path.of("static");
        Files.createDirectories(staticDir);

        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.runMigrations();

        BookRepository repository = new JdbcBookRepository(dbConfig.getDataSource());
        OpenLibraryService openLibraryService = new OpenLibraryService(repository);
        BookController controller = new BookController(repository, openLibraryService);
        Router router = createRouter(controller);

        StaticFileHandler staticHandler = new StaticFileHandler(staticDir);
        router.setFallbackHandler(staticHandler::handle);

        String portEnv = System.getenv("APP_PORT");
        int port = portEnv != null ? Integer.parseInt(portEnv) : 8080;
        HttpServer server = new HttpServer(port, router);
        server.start();
        System.out.println("Bookshelf server started on port " + port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            openLibraryService.shutdown();
            server.stop();
            dbConfig.close();
        }));

        // Block main thread to keep JVM alive (accept thread is daemon)
        Thread.currentThread().join();
    }

    public static Router createRouter(BookController controller) {
        Router router = new Router();
        router.addRoute("GET", "/books", controller::handleGetBooks);
        router.addRoute("POST", "/books", controller::handleCreateBook);
        router.addRoute("POST", "/books/re-enrich", controller::handleReEnrich);
        router.addRoute("GET", "/books/{id}", controller::handleGetBook);
        router.addRoute("PUT", "/books/{id}", controller::handleUpdateBook);
        router.addRoute("DELETE", "/books/{id}", controller::handleDeleteBook);
        router.addRoute("GET", "/books/isbn/{isbn}", controller::handleGetBookByIsbn);
        router.addRoute("GET", "/books/{id}/cover", controller::handleGetCover);
        return router;
    }
}
