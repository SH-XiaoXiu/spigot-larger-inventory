package cn.xiuxius.mc.largerinventory.i18n;

/**
 * 消息键常量定义
 * 命名规范: category.subcategory.action/description
 */
public final class MessageKeys {

    // ==================== 前缀 ====================
    public static final String PREFIX = "prefix";

    private MessageKeys() {
    }

    // ==================== 命令相关 ====================
    public static final class Command {
        // 帮助
        public static final String HELP_TITLE = "command.help.title";
        public static final String HELP_FORCERESET = "command.help.forcereset";
        public static final String HELP_OPENCONTAINER = "command.help.opencontainer";
        public static final String HELP_RELOAD = "command.help.reload";
        public static final String HELP_INFO = "command.help.info";
        public static final String HELP_BYPASS = "command.help.bypass";
        public static final String HELP_GOTO = "command.help.goto";
        public static final String HELP_NAME = "command.help.name";
        // name 命令
        public static final String NAME_SET = "command.name.set";
        public static final String NAME_CLEARED = "command.name.cleared";
        public static final String NAME_TOO_LONG = "command.name.too-long";
        // 权限
        public static final String NO_PERMISSION = "command.error.no-permission";
        public static final String PLAYER_ONLY = "command.error.player-only";
        // forcereset 命令
        public static final String FORCERESET_PLAYERS_ONLINE = "command.forcereset.players-online";
        public static final String FORCERESET_NO_LIMIT = "command.forcereset.no-limit";
        public static final String FORCERESET_START = "command.forcereset.start";
        public static final String FORCERESET_COMPLETE = "command.forcereset.complete";
        public static final String FORCERESET_COUNT = "command.forcereset.count";
        public static final String FORCERESET_OVERFLOW = "command.forcereset.overflow";
        public static final String FORCERESET_RETRIEVE_HINT = "command.forcereset.retrieve-hint";
        // opencontainer 命令
        public static final String OPENCONTAINER_PLAYER_NOT_ONLINE = "command.opencontainer.player-not-online";
        public static final String OPENCONTAINER_NO_ITEMS = "command.opencontainer.no-items";
        // reload 命令
        public static final String RELOAD_SUCCESS = "command.reload.success";
        public static final String RELOAD_VALIDATION_FAILED = "command.reload.validation-failed";
        // info 命令
        public static final String INFO_TITLE = "command.info.title";
        public static final String INFO_PLAYER = "command.info.player";
        public static final String INFO_CURRENT_PAGE = "command.info.current-page";
        public static final String INFO_MAX_PAGE = "command.info.max-page";
        public static final String INFO_CONFIG_LIMIT = "command.info.config-limit";
        public static final String INFO_HANDOVER_ITEMS = "command.info.handover-items";
        public static final String INFO_TOTAL_ITEMS = "command.info.total-items";
        public static final String INFO_PLAYER_NOT_FOUND = "command.info.player-not-found";
        public static final String INFO_QUERY_FAILED = "command.info.query-failed";
        public static final String INFO_SPECIFY_PLAYER = "command.info.specify-player";
        public static final String INFO_DATA_NOT_FOUND = "command.info.data-not-found";
        public static final String INFO_GET_FAILED = "command.info.get-failed";
        public static final String INFO_EFFECTIVE_LIMIT = "command.info.effective-limit";
        public static final String INFO_NO_LIMIT = "command.info.no-limit";
        // bypass 命令
        public static final String BYPASS_CREATIVE_ONLY = "command.bypass.creative-only";
        public static final String BYPASS_ENABLED = "command.bypass.enabled";
        public static final String BYPASS_ENABLED_HINT_1 = "command.bypass.enabled-hint-1";
        public static final String BYPASS_ENABLED_HINT_2 = "command.bypass.enabled-hint-2";
        public static final String BYPASS_DISABLED = "command.bypass.disabled";
        public static final String BYPASS_SLOT_NOT_EMPTY = "command.bypass.slot-not-empty";
        // goto 命令
        public static final String GOTO_USAGE = "command.goto.usage";
        public static final String GOTO_INVALID_PAGE = "command.goto.invalid-page";
        public static final String GOTO_OUT_OF_RANGE = "command.goto.out-of-range";
        public static final String GOTO_SUCCESS = "command.goto.success";

        private Command() {
        }
    }

    // ==================== 交接容器 ====================
    public static final class Handover {
        public static final String CONTAINER_TITLE = "handover.container-title";
        public static final String NO_ITEMS = "handover.no-items";
        public static final String OPENED = "handover.opened";
        public static final String NOTE_READONLY = "handover.note-readonly";
        public static final String OPEN_FAILED = "handover.open-failed";
        public static final String ALL_RETRIEVED = "handover.all-retrieved";
        // 翻页按钮（交接容器专用）
        public static final String NAV_PAGE_INFO = "handover.nav.page-info";
        public static final String NAV_PREV_NAME = "handover.nav.prev-name";
        public static final String NAV_NEXT_NAME = "handover.nav.next-name";

        private Handover() {
        }
    }

    // ==================== 玩家通知 ====================
    public static final class Player {
        public static final String ITEMS_PENDING = "player.items-pending";
        public static final String RETRIEVE_HINT = "player.retrieve-hint";
        public static final String CREATIVE_OVERFLOW = "player.creative-overflow";

        private Player() {
        }
    }

    // ==================== 按钮 UI ====================
    public static final class Button {
        public static final String PREV_NAME = "button.prev.name";
        public static final String PREV_FIRST_PAGE = "button.prev.first-page";
        public static final String PREV_LORE_CURRENT = "button.prev.lore.current";
        public static final String PREV_LORE_CLICK = "button.prev.lore.click";
        public static final String PREV_LORE_CANNOT = "button.prev.lore.cannot";
        public static final String NEXT_NAME = "button.next.name";
        public static final String NEXT_LAST_PAGE = "button.next.last-page";
        public static final String NEXT_LORE_CURRENT = "button.next.lore.current";
        public static final String NEXT_LORE_MAX = "button.next.lore.max";
        public static final String NEXT_LORE_CLICK = "button.next.lore.click";
        public static final String NEXT_LORE_CANNOT = "button.next.lore.cannot";
        public static final String PAGE_NAME = "button.page-name";

        private Button() {
        }
    }

    // ==================== 日志消息 ====================
    public static final class Log {
        // 启动/关闭
        public static final String PLUGIN_ENABLED = "log.plugin.enabled";
        public static final String PLUGIN_DISABLED = "log.plugin.disabled";
        public static final String BUTTON_POSITIONS = "log.plugin.button-positions";
        public static final String MAX_PAGES = "log.plugin.max-pages";
        public static final String SCHEDULED_TASK_STARTED = "log.plugin.scheduled-task-started";
        // 配置
        public static final String CONFIG_VALIDATION_FAILED = "log.config.validation-failed";
        public static final String CONFIG_BUTTON_SLOT_INVALID = "log.config.button-slot-invalid";
        public static final String CONFIG_BUTTON_SLOTS_SAME = "log.config.button-slots-same";
        public static final String SOUND_INVALID = "log.config.sound-invalid";
        // 通用日志
        public static final String PLAYER_PROCESSING_ERROR = "log.player.processing-error";
        public static final String BUTTON_CONFLICT_SAVE_FAILED = "log.page.button-conflict-save-failed";
        public static final String CROSS_PAGE_PRELOAD_FAILED = "log.page.cross-page-preload-failed";
        public static final String ALL_PAGES_LOAD_FAILED = "log.page.all-pages-load-failed";
        public static final String ALL_PAGES_CLEAR_FAILED = "log.page.all-pages-clear-failed";
        public static final String CROSS_PAGE_PICKUP_ERROR = "log.page.cross-page-pickup-error";
        // 数据库
        public static final String DB_INIT_COMPLETE = "log.db.init-complete";
        public static final String DB_DRIVER_NOT_FOUND = "log.db.driver-not-found";
        public static final String DB_INIT_FAILED = "log.db.init-failed";
        public static final String DB_CONNECTION_CLOSED = "log.db.connection-closed";
        public static final String DB_MIGRATION_START = "log.db.migration-start";
        public static final String DB_MIGRATION_COMPLETE = "log.db.migration-complete";
        public static final String DB_MIGRATION_FAILED = "log.db.migration-failed";
        // 玩家数据
        public static final String PLAYER_DATA_LOADED = "log.player.data-loaded";
        public static final String PLAYER_DATA_LOAD_FAILED = "log.player.data-load-failed";
        public static final String PLAYER_DATA_SAVE_FAILED = "log.player.data-save-failed";
        public static final String PLAYER_INVENTORY_RESET = "log.player.inventory-reset";
        // 页面操作
        public static final String PAGE_LOAD_FAILED = "log.page.load-failed";
        public static final String PAGE_WRITE_FAILED = "log.page.write-failed";
        public static final String PAGE_METADATA_UPDATE_FAILED = "log.page.metadata-update-failed";
        public static final String PAGE_LRU_EVICT_FAILED = "log.page.lru-evict-failed";
        public static final String PAGE_RELOAD_FAILED = "log.page.reload-failed";
        public static final String DIRTY_PAGE_FLUSHED = "log.page.dirty-flushed";
        // 交接容器
        public static final String HANDOVER_CREATED = "log.handover.created";
        public static final String HANDOVER_CREATE_FAILED = "log.handover.create-failed";
        public static final String HANDOVER_OPEN_FAILED = "log.handover.open-failed";
        public static final String HANDOVER_ITEM_TAKEN = "log.handover.item-taken";
        public static final String HANDOVER_UPDATE_FAILED = "log.handover.update-failed";
        public static final String HANDOVER_DESTROYED = "log.handover.destroyed";
        public static final String HANDOVER_AUTO_DESTROYED = "log.handover.auto-destroyed";
        public static final String HANDOVER_STATUS_CHECK_FAILED = "log.handover.status-check-failed";

        private Log() {
        }
    }
}
