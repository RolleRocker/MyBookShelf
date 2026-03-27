package com.bookshelf;

import com.bookshelf.adapter.in.http.AuthController;
import com.bookshelf.adapter.in.http.BookController;
import com.bookshelf.adapter.in.http.BookCsvController;
import com.bookshelf.adapter.in.http.BookStatsController;
import com.bookshelf.adapter.in.http.GoalController;
import com.bookshelf.adapter.in.http.ShelfController;
import com.bookshelf.adapter.in.mcp.McpController;
import com.bookshelf.adapter.out.auth.GoogleTokenVerifier;
import com.bookshelf.adapter.out.auth.JwtUtil;
import com.bookshelf.adapter.out.enrichment.BookEnrichmentService;
import com.bookshelf.adapter.out.enrichment.OpenLibraryClient;
import com.bookshelf.adapter.out.persistence.DatabaseConfig;
import com.bookshelf.adapter.out.persistence.JdbcBookRepository;
import com.bookshelf.adapter.out.persistence.JdbcGoalRepository;
import com.bookshelf.adapter.out.persistence.JdbcShelfRepository;
import com.bookshelf.adapter.out.persistence.JdbcUserRepository;
import com.bookshelf.domain.port.out.BookRepository;
import com.bookshelf.domain.port.out.GoalRepository;
import com.bookshelf.domain.port.out.ShelfRepository;
import com.bookshelf.domain.port.out.UserRepository;
import com.bookshelf.framework.http.AuthMiddleware;
import com.bookshelf.framework.http.HttpServer;
import com.bookshelf.framework.http.Router;
import com.bookshelf.framework.http.StaticFileHandler;
import java.nio.file.Files;
import java.util.Set;
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
        OpenLibraryClient openLibraryClient = new OpenLibraryClient();
        BookEnrichmentService openLibraryService = new BookEnrichmentService(
            repository,
            openLibraryClient
        );
        BookController controller = new BookController(
            repository,
            openLibraryService,
            shelfRepository
        );
        BookStatsController statsController = new BookStatsController(repository);
        BookCsvController csvController = new BookCsvController(repository, openLibraryService);
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
        String googleClientId = System.getenv("GOOGLE_CLIENT_ID");
        GoogleTokenVerifier googleTokenVerifier = null;
        if (googleClientId != null && !googleClientId.isEmpty()) {
            googleTokenVerifier = new GoogleTokenVerifier(googleClientId);
            logger.info("Google OAuth enabled (client ID configured)");
        }
        AuthController authController = new AuthController(
            userRepository, jwtUtil, googleTokenVerifier, googleClientId);
        AuthMiddleware authMiddleware = new AuthMiddleware(
            jwtUtil,
            Set.of("/health", "/auth/config"),
            Set.of("/auth/register", "/auth/login", "/auth/google", "/mcp"),
            Set.of("/books", "/shelves", "/goals", "/auth", "/mcp")
        );
        Router router = createRouter(
            controller,
            statsController,
            csvController,
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
        BookStatsController statsController,
        BookCsvController csvController,
        ShelfController shelfController,
        McpController mcpController,
        GoalController goalController,
        AuthController authController
    ) {
        Router router = new Router();

        // Health check for cloud load balancers
        router.addRoute("GET", "/health", req ->
            new com.bookshelf.framework.http.HttpResponse(
                200, "OK", java.util.Map.of("Content-Type", "application/json"),
                "{\"status\":\"ok\"}"
            )
        );

        router.addRoute("GET", "/books", controller::handleGetBooks);
        router.addRoute("POST", "/books", controller::handleCreateBook);
        router.addRoute("POST", "/books/re-enrich", controller::handleReEnrich);
        router.addRoute("GET", "/books/stats", statsController::handleGetStats);
        router.addRoute("GET", "/books/export", csvController::handleExportBooks);
        router.addRoute("POST", "/books/import", csvController::handleImportBooks);
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
            router.addRoute("POST", "/auth/google", authController::handleGoogleLogin);
            router.addRoute("GET", "/auth/config", authController::handleGetAuthConfig);
        }

        return router;
    }
}
