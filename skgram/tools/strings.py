#!/usr/bin/env python3
"""UI strings for every language, in one table.

Run `python3 tools/strings.py` after editing; it regenerates
shared/src/commonMain/kotlin/com/skgram/i18n/Strings.kt.
Columns: key, English, 简体中文, 繁體中文, 한국어, 日本語. Placeholders use %1$s.
"""
import os

ROWS = [
("setup_title","Connect to Telegram","连接 Telegram","連接 Telegram","Telegram 연결","Telegram に接続"),
("setup_desc","Enter the API ID and API hash of your app from my.telegram.org. They stay on this device.","请输入你在 my.telegram.org 创建的应用的 API ID 和 API Hash，仅保存在本机。","請輸入你在 my.telegram.org 建立的應用程式的 API ID 與 API Hash，只會儲存在本機。","my.telegram.org에서 만든 앱의 API ID와 API Hash를 입력하세요. 이 기기에만 저장됩니다.","my.telegram.org で作成したアプリの API ID と API Hash を入力してください。この端末にのみ保存されます。"),
("setup_open_site","Get them at my.telegram.org","前往 my.telegram.org 获取","前往 my.telegram.org 取得","my.telegram.org에서 받기","my.telegram.org で取得"),
("setup_help","If my.telegram.org says \"too many tries\" or just \"ERROR\": wait several hours (often a day), turn off any VPN/proxy, and try from a phone browser on mobile data. Use a plain English title and short name without the word \"Telegram\", and leave the URL empty.","如果 my.telegram.org 提示「尝试次数过多」或只显示「ERROR」：等几个小时（通常一天）再试，关闭 VPN/代理，用手机浏览器 + 移动数据访问。应用标题和简称用纯英文、不要包含「Telegram」，URL 留空。","如果 my.telegram.org 顯示「嘗試次數過多」或只顯示「ERROR」：等幾個小時（通常一天）再試，關閉 VPN/代理，用手機瀏覽器 + 行動網路連線。應用程式標題和簡稱用純英文、不要包含「Telegram」，URL 留空。","my.telegram.org에서 \"시도 횟수 초과\" 또는 \"ERROR\"가 뜨면: 몇 시간(보통 하루) 기다리고, VPN/프록시를 끄고, 휴대폰 브라우저와 모바일 데이터로 시도하세요. 제목과 짧은 이름은 \"Telegram\"이 없는 영문으로, URL은 비워 두세요.","my.telegram.org で「試行回数が多すぎます」や「ERROR」が出たら：数時間（多くは1日）待ち、VPN/プロキシをオフにして、スマホのブラウザとモバイルデータで試してください。タイトルと短い名前は「Telegram」を含まない英字にし、URL は空欄にします。"),
("api_id","API ID","API ID","API ID","API ID","API ID"),
("api_hash","API hash","API Hash","API Hash","API Hash","API Hash"),
("reenter_api","Re-enter API ID / hash","重新填写 API ID / Hash","重新填寫 API ID / Hash","API ID / Hash 다시 입력","API ID / Hash を入力し直す"),
("next","NEXT","下一步","下一步","다음","次へ"),
("phone_title","Your phone number","你的手机号码","你的手機號碼","전화번호","電話番号"),
("phone_desc","Enter your number with the country code.","请输入带国家区号的手机号码。","請輸入含國碼的手機號碼。","국가 번호를 포함해 입력하세요.","国番号を含めて入力してください。"),
("phone_hint","Phone number","手机号码","手機號碼","전화번호","電話番号"),
("code_title","Enter code","输入验证码","輸入驗證碼","코드 입력","コードを入力"),
("code_desc","We sent a code to %1$s via Telegram or SMS.","验证码已通过 Telegram 或短信发送到 %1$s。","驗證碼已透過 Telegram 或簡訊傳送至 %1$s。","%1$s(으)로 Telegram 또는 SMS 코드를 보냈습니다.","%1$s に Telegram または SMS でコードを送信しました。"),
("code_hint","Code","验证码","驗證碼","코드","コード"),
("password_title","Two-step verification","两步验证","兩步驟驗證","2단계 인증","2段階認証"),
("password_desc","Your account is protected with an additional password.","你的账号设置了额外密码。","你的帳號設有額外密碼。","계정에 추가 비밀번호가 설정되어 있습니다.","アカウントは追加パスワードで保護されています。"),
("password_hint","Password","密码","密碼","비밀번호","パスワード"),
("password_hint_fmt","Password hint: %1$s","密码提示：%1$s","密碼提示：%1$s","비밀번호 힌트: %1$s","パスワードのヒント：%1$s"),
("email_title","Login email","登录邮箱","登入電子郵件","로그인 이메일","ログイン用メール"),
("email_desc","Telegram asks for an email address to protect your account.","Telegram 要求设置邮箱以保护你的账号。","Telegram 要求設定電子郵件以保護你的帳號。","Telegram이 계정 보호를 위해 이메일을 요구합니다.","Telegram がアカウント保護のためメールアドレスを求めています。"),
("email_code_desc","Enter the code sent to your email.","请输入发送到邮箱的验证码。","請輸入寄到電子郵件的驗證碼。","이메일로 받은 코드를 입력하세요.","メールに届いたコードを入力してください。"),
("registration_unsupported","This number has no Telegram account yet. Please sign up in the official app first, then log in here.","该号码尚未注册 Telegram，请先在官方客户端注册后再登录。","此號碼尚未註冊 Telegram，請先在官方應用程式註冊後再登入。","이 번호는 Telegram 계정이 없습니다. 공식 앱에서 먼저 가입한 후 로그인하세요.","この番号の Telegram アカウントはまだありません。公式アプリで登録してからログインしてください。"),
("waiting_network","Waiting for network…","等待网络…","等待網路…","네트워크 대기 중…","ネットワーク待機中…"),
("connecting","Connecting…","连接中…","連線中…","연결 중…","接続中…"),
("updating","Updating…","更新中…","更新中…","업데이트 중…","更新中…"),
("search","Search","搜索","搜尋","검색","検索"),
("search_hint","Search chats","搜索聊天","搜尋聊天","채팅 검색","チャットを検索"),
("settings","Settings","设置","設定","설정","設定"),
("tab_all","All","全部","全部","전체","すべて"),
("tab_unread","Unread","未读","未讀","읽지 않음","未読"),
("tab_private","Private","私聊","私訊","개인","個人"),
("tab_groups","Groups","群组","群組","그룹","グループ"),
("tab_channels","Channels","频道","頻道","채널","チャンネル"),
("tab_secret","Secret","私密","私密","비밀","シークレット"),
("no_chats","No chats here yet","这里还没有聊天","這裡還沒有聊天","아직 채팅이 없습니다","まだチャットはありません"),
("you","You","你","你","나","あなた"),
("pin","Pin","置顶","置頂","고정","ピン留め"),
("unpin","Unpin","取消置顶","取消置頂","고정 해제","ピン留めを解除"),
("mark_read","Mark as read","标为已读","標為已讀","읽음으로 표시","既読にする"),
("copy_id","Copy ID","复制 ID","複製 ID","ID 복사","ID をコピー"),
("copied","Copied","已复制","已複製","복사됨","コピーしました"),
("message_hint","Message","消息","訊息","메시지","メッセージ"),
("send","Send","发送","傳送","보내기","送信"),
("reply","Reply","回复","回覆","답장","返信"),
("copy","Copy","复制","複製","복사","コピー"),
("translate","Translate","翻译","翻譯","번역","翻訳"),
("repeat","+1 Repeat","+1 复读","+1 複讀","+1 따라 보내기","+1 リピート"),
("delete","Delete","删除","刪除","삭제","削除"),
("translating","Translating…","翻译中…","翻譯中…","번역 중…","翻訳中…"),
("translation_failed","Translation failed","翻译失败","翻譯失敗","번역 실패","翻訳に失敗しました"),
("replying_to","Reply to %1$s","回复 %1$s","回覆 %1$s","%1$s에게 답장","%1$s に返信"),
("edited","edited","已编辑","已編輯","수정됨","編集済み"),
("error_fmt","Error: %1$s","错误：%1$s","錯誤：%1$s","오류: %1$s","エラー：%1$s"),
("type_private","Private chat","私聊","私人聊天","개인 채팅","個人チャット"),
("type_group","Group","群组","群組","그룹","グループ"),
("type_channel","Channel","频道","頻道","채널","チャンネル"),
("type_secret","Secret chat","私密聊天","私密聊天","비밀 채팅","シークレットチャット"),
("msg_photo","Photo","图片","相片","사진","写真"),
("msg_video","Video","视频","影片","동영상","動画"),
("msg_sticker","Sticker","贴纸","貼圖","스티커","スタンプ"),
("msg_voice","Voice message","语音消息","語音訊息","음성 메시지","ボイスメッセージ"),
("msg_audio","Audio","音频","音訊","오디오","オーディオ"),
("msg_document","File","文件","檔案","파일","ファイル"),
("msg_animation","GIF","GIF","GIF","GIF","GIF"),
("msg_call_in","Incoming call","来电","來電","수신 통화","着信"),
("msg_call_out","Outgoing call","去电","撥出通話","발신 통화","発信"),
("msg_call_missed","Missed call","未接来电","未接來電","부재중 통화","不在着信"),
("msg_unsupported","Message type not supported yet","暂不支持的消息类型","暫不支援的訊息類型","아직 지원하지 않는 메시지","未対応のメッセージ"),
("language","Language","语言","語言","언어","言語"),
("follow_system","Follow system","跟随系统","跟隨系統","시스템 설정","システムに従う"),
("theme","Theme","主题","主題","테마","テーマ"),
("theme_sky","Academy Sky","学园晴空","學園晴空","학원 하늘","学園の青空"),
("theme_night","Academy Night","学园夜幕","學園夜幕","학원의 밤","学園の夜"),
("theme_sakura","Sakura","樱花","櫻花","벚꽃","桜"),
("extras","SKGram extras","SKGram 增强功能","SKGram 增強功能","SKGram 추가 기능","SKGram 拡張機能"),
("ghost_mode","Ghost mode","幽灵模式","幽靈模式","고스트 모드","ゴーストモード"),
("ghost_mode_desc","Don't send read receipts or show you online.","不发送已读回执，也不显示在线状态。","不傳送已讀回條，也不顯示上線狀態。","읽음 확인과 온라인 상태를 보내지 않습니다.","既読もオンライン状態も送信しません。"),
("show_ids","Show IDs","显示 ID","顯示 ID","ID 표시","ID を表示"),
("show_ids_desc","Show chat, user and message IDs.","显示聊天、用户和消息 ID。","顯示聊天、使用者與訊息 ID。","채팅, 사용자, 메시지 ID를 표시합니다.","チャット・ユーザー・メッセージの ID を表示します。"),
("double_tap_reply","Double-tap to reply","双击回复","雙擊回覆","두 번 탭하여 답장","ダブルタップで返信"),
("double_tap_reply_desc","Double-tap a message to reply to it.","双击消息即可快速回复。","雙擊訊息即可快速回覆。","메시지를 두 번 탭하면 답장합니다.","メッセージをダブルタップで返信します。"),
("extras_more","Also built in: one-tap translation, +1 repeat, quick folders, several accounts at once, and instant blurred previews while media loads.","另外内置：一键翻译、+1 复读、快捷分类、多账号同时在线，以及媒体加载时的即时模糊预览。","另外內建：一鍵翻譯、+1 複讀、快捷分類、多帳號同時在線，以及媒體載入時的即時模糊預覽。","그 밖에: 원탭 번역, +1 따라 보내기, 빠른 폴더, 여러 계정 동시 사용, 미디어 로딩 중 즉시 미리보기.","その他：ワンタップ翻訳、+1 リピート、クイックフォルダ、複数アカウント同時利用、メディア読み込み中の即時プレビュー。"),
("notifications","Notifications","通知","通知","알림","通知"),
("notifications_desc","Notify about new messages (follows each chat's mute settings).","新消息通知（遵循每个聊天的免打扰设置）。","新訊息通知（遵循每個聊天的靜音設定）。","새 메시지 알림(채팅별 음소거 설정을 따름).","新着メッセージを通知（チャットごとのミュート設定に従います）。"),
("background","Stay connected in background","后台保持连接","背景保持連線","백그라운드 연결 유지","バックグラウンドで接続を維持"),
("background_desc","Shows a small ongoing notification so messages arrive instantly.","显示一个常驻通知，让消息即时到达。","顯示一個常駐通知，讓訊息即時送達。","작은 상주 알림을 표시해 메시지를 즉시 받습니다.","常駐通知を表示してメッセージを即時に受信します。"),
("connected_bg","SKGram is receiving messages","SKGram 正在接收消息","SKGram 正在接收訊息","SKGram이 메시지를 받는 중","SKGram がメッセージを受信中"),
("new_message","New message","新消息","新訊息","새 메시지","新着メッセージ"),
("accounts","Accounts","账号","帳號","계정","アカウント"),
("add_account","Add account","添加账号","新增帳號","계정 추가","アカウントを追加"),
("current_account","Current","当前","目前","현재","使用中"),
("new_chat","New chat","新建聊天","新增聊天","새 채팅","新しいチャット"),
("contacts","Contacts","联系人","聯絡人","연락처","連絡先"),
("no_contacts","No contacts found","没有找到联系人","沒有找到聯絡人","연락처가 없습니다","連絡先が見つかりません"),
("global_search","Public groups & channels","公开群组与频道","公開群組與頻道","공개 그룹 및 채널","公開グループとチャンネル"),
("message_action","Message","发消息","傳訊息","메시지","メッセージ"),
("secret_chat","Secret chat","私密聊天","私密聊天","비밀 채팅","シークレット"),
("join","Join","加入","加入","참여","参加"),
("joined","Joined","已加入","已加入","참여함","参加しました"),
("secret_pending","Waiting for %1$s to come online…","等待 %1$s 上线…","等待 %1$s 上線…","%1$s님의 접속을 기다리는 중…","%1$s のオンラインを待っています…"),
("secret_ready","End-to-end encrypted","端到端加密","端對端加密","종단간 암호화","エンドツーエンド暗号化"),
("secret_closed","Secret chat closed","私密聊天已关闭","私密聊天已關閉","비밀 채팅 종료됨","シークレットチャットは終了しました"),
("attach_media","Photo or video","图片或视频","相片或影片","사진 또는 동영상","写真・動画"),
("attach_file","File","文件","檔案","파일","ファイル"),
("downloading","Downloading %1$s","下载中 %1$s","下載中 %1$s","다운로드 중 %1$s","ダウンロード中 %1$s"),
("open","Open","打开","開啟","열기","開く"),
("recording","Recording %1$s — tap ✓ to send","录音中 %1$s — 点 ✓ 发送","錄音中 %1$s — 點 ✓ 傳送","녹음 중 %1$s — ✓를 눌러 보내기","録音中 %1$s — ✓ で送信"),
("mic_denied","Microphone permission is needed to record voice messages","需要麦克风权限才能录制语音","需要麥克風權限才能錄製語音","음성 녹음에는 마이크 권한이 필요합니다","音声録音にはマイクの許可が必要です"),
("incoming_call","%1$s is calling you","%1$s 正在呼叫你","%1$s 正在打給你","%1$s님이 전화 중입니다","%1$s から着信中"),
("calls_unsupported","Calls aren't available on this computer yet (Intel Macs). Answer on another device, or decline here.","这台电脑暂不支持通话（Intel 芯片的 Mac）。请在其他设备接听，或在此拒绝。","這台電腦暫不支援通話（Intel 晶片的 Mac）。請在其他裝置接聽，或在此拒絕。","이 컴퓨터(Intel Mac)에서는 아직 통화를 지원하지 않습니다. 다른 기기에서 받거나 여기서 거절하세요.","この PC（Intel Mac）では通話に未対応です。別の端末で応答するか、ここで拒否してください。"),
("decline","Decline","拒绝","拒絕","거절","拒否"),
("accept","Accept","接听","接聽","받기","応答"),
("hang_up","Hang up","挂断","掛斷","끊기","終了"),
("call_action","Call","语音通话","語音通話","음성 통화","音声通話"),
("calling","Calling…","正在呼叫…","正在撥打…","거는 중…","発信中…"),
("call_requesting","Ringing…","等待对方接听…","等待對方接聽…","상대방 응답 대기 중…","呼び出し中…"),
("call_exchanging","Securing the call…","正在建立加密连接…","正在建立加密連線…","보안 연결 중…","暗号化接続中…"),
("call_ended","Call ended","通话已结束","通話已結束","통화 종료","通話終了"),
("call_declined","Declined","对方已拒绝","對方已拒絕","거절됨","拒否されました"),
("call_failed","Call failed: %1$s","通话失败：%1$s","通話失敗：%1$s","통화 실패: %1$s","通話失敗：%1$s"),
("call_emoji_hint","If these emoji match on both phones, the call is end-to-end encrypted.","如果双方看到的表情一致，说明通话是端到端加密的。","如果雙方看到的表情一致，代表通話是端對端加密的。","양쪽 화면의 이모지가 같으면 통화가 종단간 암호화된 것입니다.","両方の絵文字が一致していれば、通話はエンドツーエンドで暗号化されています。"),
("mute","Mute","静音","靜音","음소거","ミュート"),
("unmute","Unmute","取消静音","取消靜音","음소거 해제","ミュート解除"),
("speaker","Speaker","扬声器","揚聲器","스피커","スピーカー"),
("logout","Log out","退出登录","登出","로그아웃","ログアウト"),
("logout_confirm","Log out of this account on this device?","确定要在此设备上退出这个账号吗？","確定要在此裝置上登出這個帳號嗎？","이 기기에서 이 계정을 로그아웃할까요?","この端末でこのアカウントからログアウトしますか？"),
("quit","Quit","退出","結束","종료","終了"),
("ok","OK","确定","確定","확인","OK"),
("cancel","Cancel","取消","取消","취소","キャンセル"),
("version_fmt","SKGram %1$s · powered by TDLib","SKGram %1$s · 基于 TDLib","SKGram %1$s · 基於 TDLib","SKGram %1$s · TDLib 기반","SKGram %1$s · TDLib 搭載"),
]

LANGS = ["EN", "ZH_CN", "ZH_TW", "KO", "JA"]


def camel(key):
    a, *rest = key.split("_")
    return a + "".join(p.capitalize() for p in rest)


def kstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = os.path.join(root, "shared/src/commonMain/kotlin/com/skgram/i18n/Strings.kt")
    keys = [r[0] for r in ROWS]
    assert len(keys) == len(set(keys)), "duplicate key"
    lines = [
        "// Generated by tools/strings.py — edit the table there, not this file.",
        "package com.skgram.i18n",
        "",
        "class Strings(",
    ]
    lines += [f"    val {camel(k)}: String," for k in keys]
    lines += [")", ""]
    for i, lang in enumerate(LANGS, start=1):
        lines.append(f"internal val {lang} = Strings(")
        lines += [f"    {kstr(r[i])}," for r in ROWS]
        lines += [")", ""]
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as f:
        f.write("\n".join(lines))
    print(f"{len(ROWS)} strings x {len(LANGS)} languages -> {os.path.relpath(out, root)}")


if __name__ == "__main__":
    main()
