package com.xposed.doupp.util

/**
 * DexStringSeed —— 字符串特征映射种子表（离线生成，随包发布）。
 *
 * 由 GenSeed 对抖音 40.7.0（手机实际构建，55 dex / 437MB）全量扫描产出：
 *   关键词 -> 使用该字符串常量的类
 *
 * 运行时优先查这张表，避免每次启动都在手机上重扫 437MB（实测要好几分钟）。
 * 抖音换版本、表里查不到时，才回退到 DexStringIndex 的运行时扫描。
 */
object DexStringSeed {

    /** 种子表对应的抖音版本 */
    const val SEED_VERSION = "40.7.0"

    /** 关键词 -> 类名列表 */
    val MAP: Map<String, List<String>> = mapOf(
        "auto_play_key" to listOf("Lcom/ss/android/ugc/aweme/feed/plato/business/contentconsumption/autoplay/AutoPlayComponent;", "Lcom/ss/android/ugc/aweme/feed/plato/business/contentconsumption/autoplay/AutoPlayViewModel;", "Lkotlin/jvm/internal/ALambdaS4448S0100000_47;", "LX/1Vjw;", "LY/AObjectS425S0000000_49;"),
        "watermark" to listOf("LX/054j;", "LX/0sRR;", "LX/0tTZ;", "LX/1ai9;", "LX/0JX1;", "LX/0JX2;"),
        "share_panel" to listOf("LX/0TF7;", "LX/0TF8;", "Lcom/ss/android/ugc/aweme/im/business/dispatch/mygrouplist/MyGroupListPickerLauncher;", "LX/0c1H;", "LY/AFCollectorS9S1310000_21;", "Lcom/bytedance/android/livesdk/chatroom/viewmodule/toolbar/LiveToolbarMoreDialog;", "Lcom/bytedance/android/livesdk/chatroom/viewmodule/LiveShareWidget;", "Lcom/bytedance/android/livesdk/chatroom/viewmodule/toolbar/morepanel/business/element/AudienceShareIMElement;", "LX/0dyi;", "LX/0faZ;", "LX/0fbm;", "LX/0kmf;", "LX/0pci;", "LX/0phC;", "LX/0pj9;", "LX/0pkl;", "LX/0ptr;", "LY/ACListenerS78S0110000_28;", "LY/AObjectS350S0000000_28;", "LY/ARListenerS79S1300000_28;", "Lcom/ss/android/ugc/aweme/im/share/sharelist/IMShareContactsListAdapter;", "Lkotlin/jvm/internal/ALambdaS773S0300000_28;", "LX/05W4;", "LX/0wIU;", "LX/0wIp;", "LX/0wKh;", "LX/0wOW;", "LX/0wR8;", "LX/0wU0;", "LX/0wVy;", "LX/0wVz;", "LX/0wZN;", "LX/0wiI;", "LX/0xLL;", "LY/ACListenerS836S0100000_32;", "Lcom/ss/android/ugc/aweme/im/sdk/share/panel/SharePanelWidget;", "Lcom/ss/android/ugc/aweme/im/share/aweme/only/sharepanel/function/AwemeDesktopFunction;", "Lcom/ss/android/ugc/aweme/im/share/postscriptpanel/IMSharePostscriptPanel;", "Lcom/ss/android/ugc/aweme/refactor/douyin/share/improve/action/doushareaction/DouShareActionDefaultImpl;", "Lcom/ss/android/ugc/aweme/refactor/douyin/share/improve/action/doushareaction/DouShareActionDyliteImpl;", "Lcom/ss/android/ugc/aweme/refactor/douyin/share/improve/action/doushareaction/DouShareActionHuoshanImpl;", "Lcom/ss/android/ugc/aweme/share/business/live/pkg/LiveSharePackage${'$'}Companion;", "Lcom/ss/android/ugc/aweme/sharefeed/impl/ShareFeedImpl;", "Lcom/ss/android/ugc/aweme/sharefeed/quick/presenter/AddDesktopComponentModel;", "Lcom/ss/android/ugc/aweme/sharefeed/quick/presenter/AddDesktopComponentPresenter;", "Lcom/ss/android/ugc/aweme/sharefeed/quick/presenter/DouSharePresenter;", "Lcom/ss/android/ugc/aweme/bizmodule/fullfeed/hotcomment/NearbyHotCommentPresenter;", "Lcom/ss/android/ugc/aweme/bizmodule/fullfeed/review/NearbyReviewDisplayPresenter;", "Lcom/ss/android/ugc/aweme/profile/ui/action/ProfileMoreMessageAction;", "LX/17ru;", "Lcom/bytedance/android/live/browser/jsbridge/newmethods/OpenTimeScheduleMethod;", "Lcom/bytedance/android/livesdk/chatroom/viewmodule/sharelinkpanel/LiveLinkShareToThirdDialogFragment;", "Lcom/ss/android/ugc/aweme/feed/quick/presenter/FeedMusicCoverPresenter;", "Lkotlin/jvm/internal/ALambdaS4441S0100000_40;", "Lcom/ss/android/ugc/aweme/im/service/utils/IMModuleTag;", "LY/ACListenerS262S0200000_49;", "Lcom/ss/android/ugc/aweme/playlet/feednpsguide/PlayletFeedNpsGuideComponent;", "LX/1Xpn;", "Lcom/ss/android/ugc/aweme/minilunaplayer/views/infos/share/ShareViewController;", "Lkotlin/jvm/internal/ALambdaS4506S0100000_50;", "LX/0JTL;", "LX/1dpI;", "LX/1eZs;", "LX/0Lkx;", "Lcom/bytedance/android/shopping/store/home/presenter/ECShopTopBarBackPresenterV2;", "Lkotlin/jvm/internal/ALambdaS4414S0100000_13;"),
        "SharePanel" to listOf("LX/19hK;", "LX/1Aha;"),
        "AutoPlayController" to listOf("Lcom/ss/android/ugc/aweme/follow_shoot_fra/play_control/AutoPlayController;"),
        "live_commerce" to listOf("LX/1NlE;", "LX/1Non;"),
        "share_bottom" to listOf("LX/1L2H;"),
    )


    /** 已扫描过（含 0 命中）的关键词：运行时不再对这些词做全量扫描 */
    val SCANNED: Set<String> = setOf(
        "share_panel",
        "share_bottom",
        "share_bar",
        "SharePanel",
        "share_bottom_sheet",
        "share_hsv",
        "shareAddTool",
        "auto_play_key",
        "AutoPlayController",
        "feedShopCard",
        "live_commerce",
        "watermark",
        "CronetUrlRequest",
        "playwm",
    )
    fun classesFor(keyword: String): List<String> = MAP[keyword] ?: emptyList()
    fun isScanned(keyword: String): Boolean = SCANNED.contains(keyword)
    val covered: Int get() = MAP.size
}
