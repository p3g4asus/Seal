package com.junkfood.seal.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.junkfood.seal.MainActivity
import com.junkfood.seal.R
import com.junkfood.seal.service.Constants.What.*
import com.junkfood.seal.util.*
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.regex.Pattern


class VideoDownloadService : Service() {
    private lateinit var notificationBuilder: NotificationCompat.Builder

    class MutableLiveState : MutableLiveData<ServiceState>(ServiceState()) {
        var oldTask: DownloadTask? = null
        override fun setValue(v: ServiceState?) {
            oldTask = value!!.task
            super.setValue(v)
        }

        override fun postValue(v: ServiceState?) {
            oldTask = value!!.task
            super.postValue(v)
        }
    }

    private var allowServiceStop: Boolean? = null
    private val downloadClient = MutableLiveData<Messenger>(null)
    private val mTaskList = ConcurrentLinkedQueue<DownloadTask>()
    private var mNotificationId: Int? = null

    private val currentState = MutableLiveState()

    private val currentTaskObserver =
        Observer<ServiceState> { s ->
            val b = Bundle()
            b.putBoolean(WHAT_TASK_PROGRESS.name + "0", s.task != currentState.oldTask)
            b.putParcelable(WHAT_TASK_PROGRESS.name, s)
            sendMessage(WHAT_TASK_PROGRESS.ordinal, b)
        }

    private val mClientObserver =
        Observer<Messenger> { cli ->
            if (cli == null) {
                currentState.removeObserver(currentTaskObserver)
            } else {
                currentState.observeForever(currentTaskObserver)
            }
        }

    private val taskChangeObserver =
        Observer<ServiceState> { s ->
            if (s.task != currentState.oldTask) {
                updateNotification(s.task != null)
                if (s.task != null)
                    startDownloadVideo(s.task)
            }
        }

    private fun manageDownloadError(
        e: Exception,
        isFetchingInfo: Boolean = true,
        notificationId: Int? = null
    ) =
        serviceScope.launch {
            e.printStackTrace()
            val b = Bundle()
            b.putParcelable(WHAT_TASK_PROGRESS.name, currentState.value)
            sendError(
                WHAT_TASK_PROGRESS.ordinal,
                if (isFetchingInfo) R.string.download_error_msg else R.string.fetch_info_error_msg,
                message = e.message
            )
            notificationId?.let {
                NotificationUtil.finishNotification(
                    notificationId, text = context.getString(R.string.download_error_msg),
                )
            }
        }

    private fun parsePlaylistInfo(task: DownloadTask, to: Messenger?) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Downloading playlist info")
                val playlistSize = DownloadUtilService.getPlaylistSize(task.url)
                Log.d(TAG, "Downloading playlist info ps=" + playlistSize)
                val b = Bundle()
                if (playlistSize > 1) {
                    b.putParcelable(WHAT_APPEND_TASK_ASK.name, task)
                    sendMessage(
                        WHAT_APPEND_TASK_ASK.ordinal,
                        data = b,
                        arg1 = playlistSize,
                        to = to
                    )
                } else if (playlistSize == 1) {
                    mTaskList.add(task)
                    b.putParcelable(WHAT_APPEND_TASK.name, task)
                    sendMessage(WHAT_APPEND_TASK.ordinal, b, to)
                    taskProcess()
                } else
                    throw Exception("Empty Playlist")
            } catch (e: Exception) {
                sendError(
                    WHAT_APPEND_TASK_ASK.ordinal,
                    R.string.fetch_info_error_msg,
                    to = to,
                    message = e.message
                )
            }
        }
    }

    private suspend fun downloadVideo(index: Int = 1): List<String>? {
        with(currentState) {
            val url = value!!.task!!.url
            var fileNames: List<String>? = null
            lateinit var videoInfo: VideoInfo
            try {
                videoInfo = DownloadUtilService.fetchVideoInfo(url, index)
            } catch (e: Exception) {
                manageDownloadError(e)
                return ArrayList()
            }
            Log.d(TAG, "downloadVideo: $index" + videoInfo.title)
            postValue(
                value!!.copy(
                    state = value!!.state.copy(
                        progress = 0f,
                        showVideoCard = true,
                        fileNames = null,
                        videoTitle = videoInfo.title,
                        videoAuthor = videoInfo.uploader ?: "null",
                        videoThumbnailUrl = TextUtil.urlHttpToHttps(videoInfo.thumbnail ?: "")
                    )
                )
            )
            val notificationId = (url + index).hashCode()
            var intent: Intent? = null
            var downloadResultTemp: DownloadUtilService.Result =
                DownloadUtilService.Result.failure()
            try {
                mNotificationId = notificationId
                NotificationUtil.makeNotification(notificationId, videoInfo.title)
                downloadResultTemp =
                    DownloadUtilService.downloadVideo(
                        videoInfo,
                        value!!.task!!
                    ) { progress, _, line ->
                        postValue(
                            value!!.copy(
                                state = value!!.state.copy(
                                    progress = progress,
                                    progressText = line
                                )
                            )
                        )
                        if (mNotificationId != null)
                            NotificationUtil.updateNotification(
                                notificationId,
                                progress = progress.toInt(),
                                text = line
                            )
                    }
                intent = FileUtil.createIntentForOpenFileService(downloadResultTemp)
            } catch (e: Exception) {
                manageDownloadError(e, false, notificationId)
            }
            mNotificationId = null
            NotificationUtil.finishNotification(
                notificationId,
                title = videoInfo.title,
                text = context.getString(R.string.download_finish_notification),
                intent = if (intent != null) PendingIntent.getActivity(
                    context,
                    0,
                    FileUtil.createIntentForOpenFileService(downloadResultTemp),
                    PendingIntent.FLAG_IMMUTABLE
                ) else null
            )
            if (intent != null) {
                fileNames = downloadResultTemp.filePath
            }
            return fileNames
        }
    }

    private fun downloadWithCustomCommands() {
        with(currentState) {
            val task = value!!.task!!
            val url = task.url
            val request = YoutubeDLRequest(url)
            request.addOption("-P", "${task.settings.videoDownloadDir}/")
            val m =
                Pattern.compile("\"([^\"]*)\"|(\\S+)")
                    .matcher(task.settings.command!!)
            while (m.find()) {
                if (m.group(1) != null) {
                    request.addOption(m.group(1))
                } else {
                    request.addOption(m.group(2))
                }
            }
            postValue(
                value!!.copy(
                    state = value!!.state.copy(
                        progress = 0f,
                        showVideoCard = false,
                        fileNames = null,
                    )
                )
            )
            serviceScope.launch(Dispatchers.IO) {
                try {
                    if (url.isNotEmpty())
                        with(DownloadUtilService.fetchVideoInfo(url)) {
                            if (!title.isNullOrEmpty() and !thumbnail.isNullOrEmpty())
                                postValue(
                                    value!!.copy(
                                        state = value!!.state.copy(
                                            videoTitle = title,
                                            videoThumbnailUrl = TextUtil.urlHttpToHttps(thumbnail),
                                            videoAuthor = uploader ?: "null",
                                            showVideoCard = true
                                        )
                                    )
                                )
                        }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            val notificationId = url.hashCode()
            NotificationUtil.makeNotification(
                notificationId,
                title = context.getString(R.string.execute_command_notification),
                text = ""
            )
            mNotificationId = notificationId
            serviceScope.launch(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().execute(request) { progress, _, line ->
                        postValue(
                            value!!.copy(
                                state = value!!.state.copy(
                                    progress = progress,
                                    progressText = line
                                )
                            )
                        )
                        if (mNotificationId != null)
                            NotificationUtil.updateNotification(
                                notificationId,
                                progress = progress.toInt(),
                                text = line
                            )
                    }
                    NotificationUtil.finishNotification(
                        notificationId,
                        title = context.getString(R.string.download_success_msg),
                        text = null,
                        intent = null
                    )
                    mNotificationId = null
                } catch (e: Exception) {
                    manageDownloadError(e, false, notificationId)
                    return@launch
                }
            }
        }
    }

    private suspend fun downloadVideoInPlaylistByIndexRange(): List<String>? {
        with(currentState) {
            val currentTask = value!!.task!!
            val indexRange = IntRange(currentTask.startItem, currentTask.endItem)
            var curIdx = 0
            var itCnt = indexRange.last - indexRange.first + 1
            var fileNames: List<String>? = null
            postValue(
                value!!.copy(
                    state = value!!.state.copy(
                        downloadItemCount = itCnt,
                        currentIndex = curIdx
                    )
                )
            )
            for (index in indexRange) {
                Log.d(TAG, "Downloading $index")
                if (curIdx >= itCnt)
                    break
                val st = value!!.state.copy(
                    currentIndex = index - indexRange.first + 1,
                    downloadItemCount = itCnt,
                    fileNames = if (fileNames!= null) (fileNames.ifEmpty { null }) else value!!.state.fileNames
                )
                postValue(
                    value!!.copy(
                        state = st
                    )
                )
                fileNames = downloadVideo(index)
                curIdx = value!!.state.currentIndex
                itCnt = value!!.state.downloadItemCount
            }
            return fileNames
        }
    }

    private fun startDownloadVideo(task: DownloadTask) {
        serviceScope.launch(Dispatchers.IO) {
            var fileNames: List<String>? = null
            if (task.settings.isCustom())
                downloadWithCustomCommands()
            else if (task.settings.downloadPlaylist && task.endItem - task.startItem >= 0 && task.endItem > 0 && task.startItem > 0)
                fileNames = downloadVideoInPlaylistByIndexRange()
            else
                fileNames = downloadVideo()
            finishProcessing(fileNames)
        }
    }

    private fun finishProcessing(fileNames: List<String>? = null) {
        taskProcess(true, fileNames)
    }

    private fun updateYtDlp(to: Messenger) {
        serviceScope.launch(Dispatchers.IO) {
            val rv: YoutubeDL.UpdateStatus
            try {
                rv = YoutubeDL.getInstance().updateYoutubeDL(context)
            } catch (e: Exception) {
                sendError(WHAT_YTDLP_VERSION.ordinal, R.string.yt_dlp_update_fail, to, e.message)
                return@launch
            }

            YoutubeDL.getInstance().version(context)?.let {
                ytdlpVersion = it
            }
            val b = Bundle()
            b.putString(WHAT_YTDLP_VERSION.name, ytdlpVersion)
            sendMessage(
                WHAT_YTDLP_VERSION.ordinal,
                b,
                to,
                arg1 = if (rv == YoutubeDL.UpdateStatus.DONE) R.string.ytdlp_version else R.string.yt_dlp_up_to_date
            )
        }
    }

    private val messageHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            //Processing messages
            when (msg.what) {
                WHAT_YTDLP_VERSION.ordinal -> {
                    Log.d(TAG, "receive messenger")
                    val b = msg.peekData()
                    val ver = b.getString(WHAT_YTDLP_VERSION.name)
                    if (ver!!.isEmpty()) {
                        updateYtDlp(msg.replyTo)
                    } else {
                        b.putString(WHAT_YTDLP_VERSION.name, ytdlpVersion)
                        sendMessage(msg.what, b, msg.replyTo)
                    }
                }
                WHAT_APPEND_TASK.ordinal -> {
                    val b = msg.peekData()
                    try {
                        b.classLoader = DownloadTask::class.java.classLoader
                        val task =
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) b.getParcelable(
                                WHAT_APPEND_TASK.name
                            ) else b.getParcelable(WHAT_APPEND_TASK.name, DownloadTask::class.java)
                        if (task?.url!!.isNotEmpty()) {
                            if (!task.settings.isCustom() && task.settings.downloadPlaylist && task.endItem == 0) {
                                parsePlaylistInfo(task, msg.replyTo)
                            } else {
                                mTaskList.add(task)
                                sendMessage(msg.what, b, msg.replyTo)
                                taskProcess()
                            }
                        } else
                            sendError(msg.what, R.string.url_empty, to = msg.replyTo)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        sendError(
                            msg.what,
                            R.string.invalid_task_error,
                            message = e.message,
                            to = msg.replyTo
                        )
                    }
                }
                WHAT_TASK_PROGRESS.ordinal -> {
                    Log.d(TAG, "New download receiver detected")
                    downloadClient.postValue(msg.replyTo)
                }
                WHAT_TASK_HALT.ordinal -> {
                    val b = msg.peekData()
                    try {
                        b.classLoader = DownloadTask::class.java.classLoader
                        val task =
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) b.getParcelable(
                                WHAT_TASK_HALT.name
                            ) else b.getParcelable(WHAT_TASK_HALT.name, DownloadTask::class.java)
                        val st = currentState.value!!.state
                        val ct = currentState.value!!.task!!
                        if (task!!.url == ct.url && st.downloadItemCount > st.currentIndex) {
                            currentState.postValue(
                                currentState.value!!.copy(
                                    state = currentState.value!!.state.copy(
                                        downloadItemCount = st.currentIndex,
                                    )
                                )
                            )
                            b.putBoolean(WHAT_TASK_HALT.name + "0", true)
                        } else {
                            b.putBoolean(WHAT_TASK_HALT.name + "0", false)
                        }
                        sendMessage(msg.what, b, msg.replyTo)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        sendError(
                            msg.what,
                            R.string.invalid_task_error,
                            message = e.message,
                            to = msg.replyTo
                        )
                    }
                }
                else -> {}
            }
        }
    }
    private val messenger: Messenger = Messenger(messageHandler)

    fun taskProcess(force: Boolean = false, fileNames: List<String>? = null) {
        with(currentState) {
            Log.d(TAG, "Filenames $fileNames")
            if (value!!.task == null || force) {
                val task = mTaskList.poll()

                postValue(
                    value!!.copy(
                        task = task,
                        state = value!!.state.copy(
                            progress = 100f,
                            progressText = "",
                            downloadItemCount = 0,
                            currentIndex = 0,
                            fileNames = if (fileNames!= null) (fileNames.ifEmpty { null }) else value!!.state.fileNames
                        )
                    )
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.i(TAG, "service bind")
        updateNotification(currentState.value!!.task != null)
        return messenger.binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "service unbind")
        downloadClient.postValue(null)
        updateNotification(true)
        return super.onUnbind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Start Service ..............................")
        context = this
        downloadClient.observeForever(mClientObserver)
        currentState.observeForever(taskChangeObserver)

        startForegroundService()
        serviceScope = CoroutineScope(SupervisorJob())
        serviceScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().init(this@VideoDownloadService)
                    FFmpeg.getInstance().init(this@VideoDownloadService)
                } catch (e: YoutubeDLException) {
                    e.printStackTrace()
                }
            }
        }
        ytdlpVersion =
            YoutubeDL.getInstance().version(this) ?: resources.getString(R.string.ytdlp_update)
    }

    fun sendError(what: Int, errorCode: Int, to: Messenger? = null, message: String? = null) {
        var b: Bundle? = null
        if (message != null) {
            b = Bundle()
            b.putString(WHAT_ERROR.name, message)
        }
        sendMessage(what, arg1 = -WHAT_ERROR.ordinal, data = b, arg2 = errorCode, to = to)
    }

    fun sendMessage(
        what: Int,
        data: Bundle? = null,
        to: Messenger? = null,
        arg1: Int = Constants.NO_ERROR,
        arg2: Int = Constants.NO_ERROR
    ) {
        if (null == downloadClient.value && to == null) {
            Log.d(TAG, "client is null")
            return
        }
        try {
            val message: Message = Message.obtain(null, what)
            message.arg1 = arg1
            message.arg2 = arg2
            message.data = data
            var dest: Messenger? = to
            if (to != null)
                downloadClient.postValue(to)
            else dest = downloadClient.value!!
            dest!!.send(message)
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
    }

    private fun startForegroundService() {

        val notification: Notification = getNotification()!!

        // Notification ID cannot be 0.
        startForeground(Constants.NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (Constants.ACTION_STOP == intent.action) {
            Log.d(TAG, "called to cancel service")
            stopForegroundService()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun updateNotification(allowStop: Boolean) {
        val notif = getNotification(allowStop)
        Log.d(TAG, "Updating notification as = " + allowStop + " newnotif? " + (notif != null))
        if (notif != null) {
            val manager = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            manager.notify(Constants.NOTIFICATION_ID, notif)
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun getNotification(allowStop: Boolean = false): Notification? {
        if (allowStop == allowServiceStop)
            return null
        allowServiceStop = allowStop
        // Create an explicit intent for an Activity in your app
        val intentMain = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntentMain =
            PendingIntent.getActivity(this, 0, intentMain, PendingIntent.FLAG_IMMUTABLE)
        val intentStop = Intent(this, VideoDownloadService::class.java)
        intentStop.action = Constants.ACTION_STOP
        val pendingIntentStop =
            PendingIntent.getService(
                this,
                0,
                intentStop,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                Constants.CHANNEL_ID,
                "VideoDownloadService Bys Seal",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            manager.createNotificationChannel(chan)
        }
        notificationBuilder = NotificationCompat.Builder(this, Constants.CHANNEL_ID)
            .setContentTitle(getText(R.string.seal_service))
            .setContentText(context.getString(R.string.seal_service_running))
            .setSmallIcon(R.drawable.seal)
            .setContentIntent(pendingIntentMain)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
        if (allowStop)
        //.setCustomContentView(RemoteViews(this.packageName, R.layout.layout_notification))
            notificationBuilder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_baseline_close_24,
                    getText(R.string.btn_stop),
                    pendingIntentStop
                ).build()
            )
        else
            notificationBuilder.clearActions()

        return notificationBuilder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Stop Service ...............................")
    }

    private fun stopForegroundService() {
        Log.d(TAG, "Stop foreground service.")
        val manager = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)!!
        manager!!.cancel(Constants.NOTIFICATION_ID)
        if (mNotificationId != null) {
            val notifId = mNotificationId!!
            mNotificationId = null
            manager!!.cancel(notifId)
        }
        sendMessage(WHAT_EXIT_REQUEST.ordinal)

        // Stop foreground service and remove the notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }

        // Stop the foreground service.
        stopSelf()
    }

    companion object {
        private const val TAG = "VideoDownloadService"
        var ytdlpVersion = ""
        lateinit var serviceScope: CoroutineScope

        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context
    }
}