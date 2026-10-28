# E2E scenarios

Each scenario is one instrumented test. They run against their own build
(`jp.harusame.tategakicho.e2e`, `jp.harusame.tategakicho.personal.e2e`), so they can run on a phone beside
the real app without touching its shelf, and no real site is reached: the network door
(`Net`) answers from fixtures set by the test, still behind its host check,
and every request asked is listed so a test can assert none was made.

The suite runs once per build: `public` (the store build) and `personal`. FullSaveTest runs in
`personal` only (it is skipped in `public`); GoneTest checks each build's own behaviour.

    ./gradlew :app:connectedPublicE2eAndroidTest :app:connectedPersonalE2eAndroidTest   # all, both builds
    ./gradlew :core:test :app:testPublicE2eUnitTest   # the JVM tests (AGP 9 runs them for the test build type)
    ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedPersonalE2eAndroidTest

Each test starts from a clean app: every screen an earlier test left open is
closed (a shelf left behind would start its own check), and taps wait until
the reader's window has focus. Notifications are read by waiting for the one
expected (posting is asynchronous). The suite passed three runs in a row.

Fixtures: `E2E.seedNarou` puts a なろう work (n9999zz — not n0000aa, whose
first episode is the bundled sample) with its episodes kept on the shelf.

## Reading (ReadingTest) — done

| | Scenario | Test |
|---|---|---|
| R1 | An episode opens from the cache, credited, with no request | opensFromTheCacheWithTheAuthorShown |
| R2 | Taps turn pages both ways; the place is saved | tapsTurnPagesAndThePlaceIsSaved |
| R3 | Past the last page, the next episode comes in (prepared, no reload) | turningPastTheEndsCrossesEpisodes |
| R4 | Before the first page, the previous episode at its last page | turningPastTheEndsCrossesEpisodes |
| R5 | After the last page of the last episode, the end card | theEndCardAfterTheLastEpisode |
| R6 | Recreated (a system setting, or the process back), the reader stays where it was | recreatingKeepsThePlace |
| R7 | An author's literal markup is text, not a script a tap runs | anAuthorsMarkupDoesNotRun |
| R8 | Back closes the bars first; the reader closes only when nothing is open | backClosesTheBarsBeforeTheReader |
| R9 | An episode that cannot be read, opened first, leaves the 目次 and the previous episode | aDeadFirstEpisodeLeavesAWayOut |

## The shelf and what is fetched (ShelfFlowTest) — done

| | Scenario | Test |
|---|---|---|
| R10 | The 目次 is read from the work's page once, marks the current episode, and a jump opens that episode | theTocJumpsToAnEpisode |
| R10b | Chapters are boxes: a band, then its episodes a step in; episodes before the first chapter are not indented; scrolled into a chapter, only its band is at the top | theTocShowsChaptersAsBandsThatStayAtTheTop |
| R10c | A work without chapters lists its episodes flat | aTocWithoutChaptersIsNotIndented |
| R10d | A chapter whose heading ends one 目次 page and whose episodes begin the next is one box | aChapterAcrossTwoTocPagesStaysOneBox |
| S1 | A shared なろう episode URL adds the work (one API request) and opens that episode; the shelf made again does not add it twice or move the place | aSharedEpisodeIsAddedAndOpened |
| S4 | Removing a work deletes everything kept for it | removingAWorkDeletesWhatIsKept |
| P1 | Read-ahead asks for the next episodes only, in order and spaced out; nothing for a work taken off the shelf | readAheadAsksForTheNextEpisodesOnly |
| P2 | Aa offers 1/3/5/15/20 episodes of read-ahead; the app honours 15 and 20, and a larger stored value stops at 20 | readAheadGoesUpToTwenty |
| R8b | After a jump from the 目次 reloads the page, Back closes the reader (no overlay left registered) | backWorksAfterAJumpReloadsThePage |
| R13 | Opening part-way saves no page 0 and logs no page read until one is turned | openingPartWayReadsNothingYet (ReadingTest) |
| R14 | 字下げ 付ける indents a paragraph written flush, at once and after reopening | theIndentSettingIndentsFlushParagraphs (ReadingTest) |
| R14b | 英字 すべて縦向き stands an English phrase upright (full-width), at once and after reopening; a short word is upright either way | theUprightSettingStandsEnglishUpright (ReadingTest) |
| R15 | 行間, 上下余白 and 左右余白 change the layout at once, keep the share read, and stay after reopening | lineHeightAndMarginsChangeTheLayout (ReadingTest) |
| R16 | A 青空文庫 book hides 空行, 字下げ and 英字; a なろう episode shows them | aozoraBooksHideTheWebFormattingRows (ReadingTest) |
| R17 | The 目次 marks the episodes kept in the app and counts them | theTocMarksKeptEpisodes (ShelfFlowTest) |
| R18 | The page slider runs right to left: its right end is page 1, its left end the last page | theSliderRunsRightToLeft (ReadingTest) |
| R19 | A long press on the bar's count switches the slider to episodes: moving it shows number and title and fetches nothing; letting go on an episode not kept fetches that one and opens its first page; the bars shown again span the pages | holdingTheCountMovesThroughEpisodes (ReadingTest) |
| R19c | Until the count has been held once, the bars show a hint above it, at most 3 times, saved; a hold stops it; a tap on the episode title page closes the bars on the page being read | theCountsHintShowsUntilItIsHeld (ReadingTest) |
| R20 | The base of the thumb (low at the edge, in a bottom corner, or landing during a tap) does not turn; a narrow fingertip at mid height turns; a tap at the side with the other hand's finger resting on the other half turns; a resting palm does not keep the thumb's tap, nor does a base landing beside it take the gesture; a finger landing mid-tap does not take it; a swipe whose release never came does not block the next tap; a fingertip 14 px from the edge turns; 端の誤タッチ 防がない turns as before | theBaseOfTheThumbDoesNotTurn (ReadingTest) |
| R21 | Aa's and 作品's sheet pulled down from the top: a short pull follows and slides back; past a third of its height it closes with the bars; めくりの振動 is off until chosen, saved, and a tap still turns | aSheetPulledDownCloses (ReadingTest) |
| R22 | The tap zones: 左右反転 swaps the sides; a zone tapped in Aa cycles and the last menu zone stays; a zone set to nothing does nothing; 真ん中の幅 広い moves where the middle starts; the guide is drawn from the zones; saved | theTapZonesCanBeChanged (ReadingTest) |
| R19b | A 青空文庫 book's sections are its episodes on the page bar, all kept, named as in its 目次; nothing fetched | aBooksSectionsAreItsEpisodesOnTheSlider (ReadingTest) |
| G1 | The first book opens under the tap guide; a tap closes it without turning, and it is not shown again | shownOnceAndATapClosesItWithoutTurning (GuideTest) |
| G2 | Aa's 触る場所 shows the guide again; Back closes it before the reader | aaShowsItAgainAndBackClosesIt (GuideTest) |
| F1 | 全話を保存 fetches each episode not kept, once; kept ones are not asked for | savesEachEpisodeNotKeptOnce (FullSaveTest) |
| F2 | 全話の保存を止める ends it before the next fetch | stoppingEndsItBeforeTheNextFetch (FullSaveTest) |
| F3 | 保存した話を消す empties the cache and leaves the work and its place | clearingTheKeptEpisodesKeepsTheWork (FullSaveTest) |
| F4 | A カクヨム save stopped while queued behind another asks nothing, not even its list | aSaveStoppedWhileQueuedReachesNothing (FullSaveTest) |
| F5 | 保存容量の整理's 保存した話をすべて消す during a save stops it; nothing cleared is fetched back | clearingFromStorageStopsASave (FullSaveTest) |
| F6 | With no screen shown a save waits and fetches nothing; it goes on once a screen is shown | aSaveWaitsWhileTheAppIsNotInFront (FullSaveTest) |
| F7 | Taking the work off the shelf during a save ends it; nothing is kept for it afterwards | removingTheWorkEndsASave (FullSaveTest) |
| F8 | The long-press menu starts a save, the row shows its count, and the menu stops it | theMenuStartsShowsAndStopsASave (FullSaveTest) |
| F9 | A カクヨム save whose turn comes with no screen shown does not read the work's list | aKakuyomuSaveReadsNothingOutOfFront (FullSaveTest) |
| F10 | A カクヨム work taken off the shelf while its list is read leaves no list or folder | aListReadDuringRemovalLeavesNothing (FullSaveTest) |
| F11 | A fetch waiting its turn behind another request is not sent once the save is stopped | aFetchWaitingItsTurnIsNotSentAfterStop (FullSaveTest) |
| F12 | A fetch waiting its turn is not sent once its work is taken off the shelf | aFetchWaitingItsTurnIsNotSentAfterRemoval (FullSaveTest) |
| D1 | A work gone from its site keeps its text at the first check; a day later the store build drops it (work and place stay), the personal build keeps it | aWorkGoneADayLosesItsTextOnlyInTheStoreBuild (GoneTest) |
| D2 | A work found again is unmarked | aWorkFoundAgainIsUnmarked (GoneTest) |
| D3 | 全話を保存 is in the long-press menu of the personal build only | theMenuOffersFullSaveOnlyInThePersonalBuild (GoneTest) |
| D4 | A なろう work out of the API but still on its page (out of search) keeps its text, is not marked or reported in the store build; its page is asked at most once a day | aWorkOutOfSearchButStillOnItsPageKeepsItsText (GoneTest) |
| D5 | Store build: one 404 from the page only marks (and reports) the work; the text goes when the page still says so a day later, and the page is not asked after that | onePageAnswerOnlyMarksTheWork (GoneTest) |
| B1 | A new shelf holds the guide book, and はじめかた offers the ways to add a work | aNewShelfHoldsTheGuideAndHowToStart (GuideBookTest) |
| B2 | The guide book opens without a request, with a 目次 and no links to a site | theGuideOpensOfflineWithoutSiteLinks (GuideBookTest) |
| B3 | Taken off, the guide stays off at the next start, and ＋ puts it back | takenOffItStaysOffAndComesBackFromPlus (GuideBookTest) |
| B4 | The end of the guide book offers no link to a site | theGuidesEndCardHasNoSiteLink (GuideBookTest) |
| Y1 | Google's accessibility checks (touch targets, contrast, labels) find no error on the shelf, read log, 青空文庫, search and licence screens, in a light and a dark theme | nativeScreensPassTheChecks (A11yTest) |
| Y2 | In the reader's bars, 作品 and Aa sheets, 目次 and end card, every button and link is at least 48 × 48 px (the WebView, which Y1's checks do not see into) | readerTouchTargetsAreFullSize (A11yTest) |
| Y3 | A pill button (shelf tags; search and 青空文庫 share it) keeps 16dp at each side and 48dp of height | aPillKeepsRoomAtItsSides (A11yTest) |
| — | ScreensTest: not a check; it walks every screen for tools/screen-matrix.sh and is skipped unless run with `-e screens 1` (docs/release.md) | ScreensTest |
| T1 | The read log, opened from the shelf's ⋮, has no tabs; its back arrow returns to the shelf | theBackArrowLeavesTheReadLog |
| T2 | The read log sums up the month (days, works, episodes; the guide book left out), leads on to the year with its months and works, and Back returns to the month | theMonthSummaryLeadsToTheYearAndBack |
| L1 | Every text the licence screen lists is in the APK: the GPL notice, the GPL, the notices and each licence file they name | everyListedTextIsInTheApk (LicenceTest) |
| L2 | From the shelf's ⋮, ライセンス lists the texts and the source; a text opens in full; Back returns to the list, then to the shelf | theShelfMenuOpensTheLicencesAndBackReturns (LicenceTest) |
| K1 | カクヨム: an episode inserted before the one read; "next" opens the one after it, and the place follows | nextAfterAnInsertBefore (KakuyomuTest) |
| K1b | カクヨム: a section with no chapter after 第一章 is not filed under it in the 目次 | aSectionWithNoChapterIsNotUnderTheOneBefore |
| K2 | カクヨム: the list changes while the 目次 is open; the episode tapped is the one opened | aTocJumpAfterTheListChanged |
| K3 | カクヨム: the episode read is deleted; "next" opens the one that followed it | nextAfterTheEpisodeReadIsDeleted |
| K4 | カクヨム: an episode inserted before the place; opened from the shelf, the same episode comes back (第3話 now) | reopenedAfterAnInsertBefore |
| K5 | カクヨム: a place an earlier build kept as a number opens the same episode and is kept by its id from then on | aNumberedPlaceFromAnEarlierBuild |
| K6 | カクヨム: sharing an episode URL of a work on the shelf opens that episode | aSharedEpisodeOpensIt |
| K7 | カクヨム: the place's episode is deleted before it is opened; next and previous are the episodes either side of where it was | thePlacesEpisodeDeletedBeforeOpening |
| K8 | カクヨム: the next episode was prepared, then the list changed; turning into it shows its number now | aPreparedEpisodeShowsItsNumberNow |
| K9 | カクヨム: the episode read and the one after it are deleted while it is open; "next" opens the one after where it was | nextAfterTheEpisodeReadAndItsNextAreDeleted |
| K6b | カクヨム: sharing an episode newer than the kept list, of a work on the shelf, opens that episode | aSharedNewEpisodeOpensIt |
| K7b | カクヨム: the place's episode deleted and never kept here; "next" from the error page opens the one after where it was | thePlacesEpisodeDeletedAndNeverKept |
| K10 | カクヨム: the page bar across the episodes follows the kept list by id; letting go on 題C opens C | theEpisodeSliderOpensByIdFromTheKeptList |

## Background check (CheckWorkerTest) — done

| | Scenario | Test |
|---|---|---|
| C1 | The background check asks the API once, updates the shelf, notifies "新着 2 話" | newEpisodesAreCountedAndNotified |
| C2 | Nothing new notifies nothing; episodes already counted are not notified again | nothingNewNotifiesNothing |
| C3 | Right after a check, the background check asks nothing | noCheckSoonAfterTheLast |
| C4 | News not looked at adds up across rounds; opening the shelf (its screen) clears it | newsAddsUpUntilTheShelfIsOpened |
| C5 | While a check is running, the background check starts none, moves no check time, notifies nothing | noSecondCheckWhileOneRuns |
| C6 | The shelf's own check asked while another runs waits for it and shows its result | theShelfsCheckTakesTheRunningOnesResult |
| C7 | If the running check ends without finishing, the shelf's check takes its own turn | theShelfsCheckRunsWhenTheOtherDidNotFinish |
| C8 | The background check tests the last check time again once it has its turn | theBackgroundCheckReadsTheTimeAgainWithItsTurn |

## Next

| | Scenario |
|---|---|
| R11 | Swipe turns with the slide; a short drag springs back |
| R12 | Volume keys turn pages when chosen, and not under the bars, 目次 or end card |
| D1 | Aa: theme, size, font, blank lines; kept across a restart, place kept on a relayout |
| D2 | The status bar option shows the bar and lays the page out below it |
| S2 | Sharing a カクヨム episode URL adds the work (work-page fixture) and opens it; the 目次 has its chapters |
| S3 | The shelf: resume card, sort orders, filter, new-episode badge after a check |
| S5 | The storage screen: read-past episodes go, the one before the place stays; 青空文庫 stays readable |
| A1 | 青空文庫: the list fetched once (fixture), 人気 keeps the ranking's own numbers, 作家 / 分類 / 検索 |
| A2 | 青空文庫: a work added and read (zip fixture), its sections turned like episodes |
| N1 | Search and rankings: filters reach the API as parameters; a stale search is not sent |
