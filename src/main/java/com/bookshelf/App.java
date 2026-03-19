package com.bookshelf;

import com.bookshelf.mcp.McpController;
import java.nio.file.Files;
import java.nio.file.Path;

public class App {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(App.class);

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
        BookEnrichmentService openLibraryService = new BookEnrichmentService(
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
        GoalRepository goalRepository = new JdbcGoalRepository(
            dbConfig.getDataSource()
        );
        GoalController goalController = new GoalController(
            goalRepository,
            repository
        );
        McpController mcpController = new McpController(
            repository,
            shelfRepository
        );
        JwtUtil jwtUtil = new JwtUtil();
        UserRepository userRepository = new JdbcUserRepository(dbConfig.getDataSource());
        AuthController authController = new AuthController(userRepository, jwtUtil);
        AuthMiddleware authMiddleware = new AuthMiddleware(jwtUtil);
        Router router = createRouter(
            controller,
            shelfController,
            mcpController,
            goalController,
            authController
        );

        StaticFileHandler staticHandler = new StaticFileHandler(staticDir);
        router.setFallbackHandler(staticHandler::handle);

        String portEnv = System.getenv("APP_PORT");
        int port = 8080;
        if (portEnv != null) {
            try {
                port = Integer.parseInt(portEnv);
            } catch (NumberFormatException e) {
                logger.warn("Invalid APP_PORT '{}', defaulting to 8080", portEnv);
            }
        }
        HttpServer server = new HttpServer(port, router, authMiddleware);
        server.start();
        logger.info("Bookshelf server started on port {}", port);

        Runtime.getRuntime().addShutdownHook(
            new Thread(() -> {
                try {
                    openLibraryService.shutdown();
                } catch (Exception e) {
                    logger.error("Shutdown error: {}", e.getMessage());
                }
                try {
                    server.stop();
                } catch (Exception e) {
                    logger.error("Shutdown error: {}", e.getMessage());
                }
                try {
                    dbConfig.close();
                } catch (Exception e) {
                    logger.error("Shutdown error: {}", e.getMessage());
                }
            })
        );

        // Block main thread to keep JVM alive (accept thread is daemon)
        Thread.currentThread().join();
    }

    public static Router createRouter(
        BookController controller,
        ShelfController shelfController,
        McpController mcpController,
        GoalController goalController,
        AuthController authController
    ) {
        Router router = new Router();
        router.addRoute("GET", "/books", controller::handleGetBooks);
        router.addRoute("POST", "/books", controller::handleCreateBook);
        router.addRoute("POST", "/books/re-enrich", controller::handleReEnrich);
        router.addRoute("GET", "/books/stats", controller::handleGetStats);
        router.addRoute("GET", "/books/export", controller::handleExportBooks);
        router.addRoute("POST", "/books/import", controller::handleImportBooks);
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

        // Goal routes
        router.addRoute("GET", "/goals", goalController::handleGetGoals);
        router.addRoute("POST", "/goals", goalController::handleCreateGoal);
        router.addRoute("GET", "/goals/{year}", goalController::handleGetGoal);
        router.addRoute("PUT", "/goals/{year}", goalController::handleUpdateGoal);
        router.addRoute("DELETE", "/goals/{year}", goalController::handleDeleteGoal);

        // MCP endpoint
        router.addRoute("POST", "/mcp", mcpController::handleMcp);

        // Auth routes
        if (authController != null) {
            router.addRoute("POST", "/auth/register", authController::handleRegister);
            router.addRoute("POST", "/auth/login", authController::handleLogin);
        }

        return router;
    }
}
