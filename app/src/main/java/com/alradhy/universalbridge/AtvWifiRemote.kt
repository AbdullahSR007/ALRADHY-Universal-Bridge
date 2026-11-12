package com.alradhy.universalbridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/*
 * ALRADHY Original interoperability implementation: Android TV Remote Service v2.
 * Wire-format descriptions: https://github.com/sonalder-darlene/libremote-android-tv/blob/main/PROTOCOL.md
 * No GPL source code is included or copied; field numbers and protocol facts are used.
 * The native TV service controls its own temporary keyboard during an IME session.
 * We never install a second TV keyboard and close IME sessions as quickly as possible.
 */

private class Pb {
    private val output = ByteArrayOutputStream()
    fun n(f: Int, n: Long): Pb { v(((f shl 3) or 0).toLong()); v(n); return this }
    fun n(f: Int, n: Int): Pb = n(f, n.toLong())
    fun b(f: Int, b: ByteArray): Pb {
        v(((f shl 3) or 2).toLong()); v(b.size.toLong()); output.write(b); return this
    }
    fun s(f: Int, s: String): Pb = b(f,s.toByteArray(Charsets.UTF_8))
    fun m(f: Int, nested: Pb): Pb = b(f,nested.bytes())
    fun bytes(): ByteArray = output.toByteArray()
    private fun v(value: Long) {
        var x = value
        do {
            val next = (x and 127).toInt()
            x = x ushr 7
            output.write(if (x == 0L) next else next or 128)
        } while (x != 0L)
    }
}
private class Field(val num: Long? = null,val bytes: ByteArray? = null)
private class Fields(data: ByteArray) {
    private val values = HashMap<Int,Field>()
    init {
        var i=0
        fun varint(): Long {
            var n=0L;var shift=0
            while(true) {
                require(i<data.size && shift<70){"Invalid protobuf varint"}
                val c=data[i++].toInt() and 255
                n=n or ((c and 127).toLong() shl shift)
                if(c and 128==0)return n
                shift+=7
            }
        }
        while(i<data.size) {
            val tag=varint().toInt();val field=tag ushr 3
            require(field>0){"Invalid protobuf field"}
            when(tag and 7) {
                0 -> values[field]=Field(num=varint())
                1 -> {require(i+8<=data.size);i+=8}
                2 -> {
                    val len=varint().toInt()
                    require(len>=0 && i+len<=data.size){"Invalid protobuf size"}
                    values[field]=Field(bytes=data.copyOfRange(i,i+len));i+=len
                }
                5 -> {require(i+4<=data.size);i+=4}
                else -> throw IllegalArgumentException("Unsupported protobuf wire type")
            }
        }
    }
    fun has(f:Int)=values.containsKey(f)
    fun n(f:Int):Long=values[f]?.num?:0
    fun bytes(f:Int)=values[f]?.bytes
    fun msg(f:Int):Fields?=bytes(f)?.let {Fields(it)}
}
private fun OutputStream.frame(payload:ByteArray) {
    require(payload.size<=1024*1024)
    var len=payload.size
    while(len>=128){write((len and 127) or 128);len=len ushr 7}
    write(len);write(payload);flush()
}
private fun InputStream.frame():ByteArray {
    var length=0;var shift=0
    while (true) {
        val c=read()
        if(c<0)throw IllegalStateException("TV closed the connection")
        require(shift<=28){"Frame length is invalid"}
        length=length or ((c and 127) shl shift)
        if(c and 128==0)break
        shift+=7
    }
    require(length in 0..1048576){"Oversized remote frame"}
    val result=ByteArray(length)
    var offset=0
    while(offset<length){val n=read(result,offset,length-offset);if(n<0)throw IllegalStateException("Incomplete remote frame");offset+=n}
    return result
}
private fun BigInteger.unsigned():ByteArray =
    toByteArray().let { if(it.size>1 && it[0]==0.toByte())it.copyOfRange(1,it.size) else it }
private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)
private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 255)}

private class TvIdentity(val keyStore:KeyStore,val certificate:X509Certificate) {
    companion object {
        private const val ALIAS="alradhy_wifiremote_v2"
        private val PASS="alradhy-identity-format-v2".toCharArray()
        @Synchronized fun get(context:Context):TvIdentity {
            val f=File(context.filesDir,"atv_v2_identity.enc")
            val store=KeyStore.getInstance("PKCS12")
            if(f.exists()) {
                val raw=decrypt(f.readBytes())
                store.load(ByteArrayInputStream(raw),PASS)
            } else {
                val rsa=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
                val now=System.currentTimeMillis()
                val name=X500Name("CN=ALRADHY-Bridge-"+java.util.UUID.randomUUID())
                val cert=JcaX509CertificateConverter().getCertificate(
                    JcaX509v3CertificateBuilder(name,BigInteger.valueOf(now),
                        Date(now-86400000),Date(now+10L*365*86400000),name,rsa.public)
                        .build(JcaContentSignerBuilder("SHA256withRSA").build(rsa.private)))
                store.load(null,PASS)
                store.setKeyEntry(ALIAS,rsa.private,PASS,arrayOf(cert))
                val bytes=ByteArrayOutputStream().also{store.store(it,PASS)}.toByteArray()
                f.writeBytes(encrypt(bytes))
            }
            return TvIdentity(store,store.getCertificate(ALIAS) as X509Certificate)
        }
        private fun aesKey():java.security.Key {
            val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
            ks.getKey("ALRADHY_WIFI_TV_ID",null)?.let{return it}
            val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
            gen.init(KeyGenParameterSpec.Builder("ALRADHY_WIFI_TV_ID",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            return gen.generateKey()
        }
        private fun encrypt(bytes:ByteArray):ByteArray {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,aesKey())
            return cipher.iv+cipher.doFinal(bytes)
        }
        private fun decrypt(bytes:ByteArray):ByteArray {
            require(bytes.size>12){"Identity store corrupted"}
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,aesKey(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            return cipher.doFinal(bytes.copyOfRange(12,bytes.size))
        }
        fun password():CharArray=PASS
    }
}
private class TvSocket(
    private val identity:TvIdentity,
    private val host:String,
    val port:Int,
    private val pinned:String?
) {
    val socket:SSLSocket
    val certificate:X509Certificate
    init {
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        km.init(identity.keyStore,TvIdentity.password())
        var server:X509Certificate?=null
        val tm=object:X509TrustManager {
            override fun getAcceptedIssuers():Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String) {}
            override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String) {
                val found=chain.firstOrNull()?:throw java.security.cert.CertificateException("Missing TV certificate")
                if(found.publicKey !is RSAPublicKey)throw java.security.cert.CertificateException("TV certificate is not RSA")
                val hash=hex(sha256(found.encoded))
                if(pinned!=null && hash!=pinned)throw java.security.cert.CertificateException("TV certificate changed: pairing is required again")
                server=found
            }
        }
        val ssl=SSLContext.getInstance("TLS")
        ssl.init(km.keyManagers,arrayOf(tm),SecureRandom())
        val s=ssl.socketFactory.createSocket() as SSLSocket
        try{
            s.connect(InetSocketAddress(host,port),5000)
            s.soTimeout=7000
            s.startHandshake()
            certificate=server?:throw IllegalStateException("No TV certificate")
            socket=s
        } catch(e:Exception){runCatching{s.close()};throw e}
    }
    fun send(bytes:ByteArray)=synchronized(socket.outputStream){socket.outputStream.frame(bytes)}
    fun read()=Fields(socket.inputStream.frame())
    fun close()=runCatching{socket.close()}
}
private class RemoteSession(val ssl:TvSocket,val ime:Boolean) {
    @Volatile var ready=false
    @Volatile var closed=false
    @Volatile var imeCounter=0
    @Volatile var fieldCounter=0
    @Volatile var lastInput=System.currentTimeMillis()
    private val latch=CountDownLatch(1)
    private val offered=if(ime) (1 or 2 or 4 or 32 or 64 or 512) else (1 or 2 or 32 or 64 or 512)
    fun start(){
        Thread({
            try{
                while(!closed) {
                    val msg=ssl.read()
                    when {
                        msg.has(1) -> {
                            val supported=msg.msg(1)?.n(1)?.toInt()?:0
                            val active=supported and offered
                            require(active and 2!=0){"TV remote service doesn't support key commands"}
                            if(ime) require(active and 4!=0){"TV remote service doesn't support native text"}
                            val device=Pb().s(1,"ALRADHY Bridge").s(2,"ALRADHY").n(3,1)
                                .s(4,"Android").s(5,"com.alradhy.universalbridge.mobile").s(6,"0.2.0")
                            ssl.send(Pb().m(1,Pb().n(1,active).m(2,device)).bytes())
                        }
                        msg.has(2) -> ssl.send(Pb().m(2,Pb().n(1,offered and (1 or 2 or 4 or 32 or 64 or 512))).bytes())
                        msg.has(8) -> ssl.send(Pb().m(9,Pb().n(1,msg.msg(8)?.n(1)?:0)).bytes())
                        msg.has(20) -> {
                            val state=msg.msg(20)
                            imeCounter=(state?.msg(1)?.n(1)?:0).toInt()
                            fieldCounter=(state?.msg(2)?.n(1)?:0).toInt()
                        }
                        msg.has(22) -> {
                            val state=msg.msg(22)?.msg(2)
                            if(state!=null)fieldCounter=state.n(1).toInt()
                        }
                        msg.has(21) -> {
                            val state=msg.msg(21)
                            if(imeCounter==0)imeCounter=(state?.n(1)?:0).toInt()
                            if(fieldCounter==0)fieldCounter=(state?.n(2)?:0).toInt()
                        }
                        msg.has(40) -> {ready=true;latch.countDown()}
                        msg.has(3) -> throw IllegalStateException("TV remote protocol rejected a message")
                    }
                }
            }catch(_:Exception){}finally{
                closed=true;ready=false;latch.countDown();ssl.close()
            }
        },"alradhy-tv-v2-"+if(ime)"ime" else "keys").apply{isDaemon=true;start()}
        if(!latch.await(7,TimeUnit.SECONDS)||!ready){
            close();throw IllegalStateException("TV remote connection not ready")
        }
    }
    fun key(code:Int) {
        check(ready&&!closed){"TV remote is disconnected"}
        ssl.send(Pb().m(10,Pb().n(1,code).n(2,3)).bytes())
    }
    fun text(text:String) {
        check(ready&&!closed&&ime){"Native TV keyboard session is not ready"}
        val edit=Pb().n(1,1).m(2,Pb().n(1,text.length).n(2,text.length).s(3,text))
        ssl.send(Pb().m(21,Pb().n(1,imeCounter).n(2,fieldCounter).m(3,edit)).bytes())
        lastInput=System.currentTimeMillis()
    }
    fun close(){closed=true;ready=false;ssl.close()}
}

/** Android mobile is the native Wi-Fi remote client and optional browser relay. */
object AtvWifiRemote {
    private var app:Context?=null
    @Volatile var host:String=""
        private set
    @Volatile var paired=false
        private set
    @Volatile var phase="idle"
        private set
    @Volatile var lastError=""
        private set
    private var pairSocket:TvSocket?=null
    private var identity:TvIdentity?=null
    private var keys:RemoteSession?=null
    private var input:RemoteSession?=null
    private var serviceListener:NsdManager.DiscoveryListener?=null
    private var nsd:NsdManager?=null
    private val found=LinkedHashMap<String,String>()
    private val pinsPrefix="cert_sha256_"
    fun init(context:Context) {
        app=context.applicationContext
        val prefs=context.getSharedPreferences("native_tv_remote",Context.MODE_PRIVATE)
        if(host.isEmpty())host=prefs.getString("last_tv_ip","").orEmpty()
        paired=host.isNotBlank()&&prefs.contains(pinsPrefix+host)
    }
    private fun context():Context=app?:throw IllegalStateException("Remote not initialized")
    private fun prefs()=context().getSharedPreferences("native_tv_remote",Context.MODE_PRIVATE)
    private fun identity():TvIdentity=identity?:TvIdentity.get(context()).also{identity=it}
    private fun validHost(ip:String):String{
        val address=InetAddress.getByName(ip.trim())
        require(address is java.net.Inet4Address && (address.isSiteLocalAddress || address.isLoopbackAddress) && !address.isLoopbackAddress){
            "Use a private LAN TV IPv4 address"
        }
        return address.hostAddress.orEmpty()
    }
    @Synchronized fun startPair(ip:String):String {
        if(BuildConfig.FLAVOR!="mobile")return "Wi-Fi Remote pairing is available from mobile/tablet app only"
        closePair()
        try{
            val candidate=validHost(ip)
            host=candidate
            val id=identity()
            val socket=TvSocket(id,candidate,6467,null)
            pairSocket=socket
            socket.send(Pb().n(1,2).n(2,200).m(10,Pb().s(1,"atvremote").s(2,"ALRADHY Universal Bridge")).bytes())
            fun check(m:Fields){require(m.n(2)==0L||m.n(2)==200L){"TV refused pairing: "+m.n(2)}}
            val ack=socket.read();check(ack);require(ack.has(11)){"TV did not acknowledge pairing request"}
            socket.send(Pb().n(1,2).n(2,200).m(20,Pb().m(1,Pb().n(1,3).n(2,6)).n(3,1)).bytes())
            val opt=socket.read();check(opt);require(opt.has(20)){"TV did not accept pairing options"}
            socket.send(Pb().n(1,2).n(2,200).m(30,Pb().m(1,Pb().n(1,3).n(2,6)).n(2,1)).bytes())
            val config=socket.read();check(config);require(config.has(31)){"TV did not display a pairing code"}
            phase="awaiting_code"
            lastError=""
            return "Check the 6-character hexadecimal pairing code on the TV"
        }catch(e:Exception){lastError=e.message?:"Pairing failed";phase="error";closePair();throw e}
    }
    @Synchronized fun finishPair(pin:String):String {
        val socket=pairSocket?:throw IllegalStateException("Start pairing first")
        try{
            val code=pin.trim().uppercase()
            require(code.matches(Regex("[0-9A-F]{6}"))){"Enter the 6-character code shown on TV"}
            val client=(identity().certificate.publicKey as RSAPublicKey)
            val server=(socket.certificate.publicKey as RSAPublicKey)
            val body=ByteArrayOutputStream().also{
                it.write(client.modulus.unsigned());it.write(client.publicExponent.unsigned())
                it.write(server.modulus.unsigned());it.write(server.publicExponent.unsigned())
                it.write(byteArrayOf(code.substring(2,4).toInt(16).toByte(),code.substring(4,6).toInt(16).toByte()))
            }.toByteArray()
            val digest=sha256(body)
            require(digest[0].toInt() and 255==code.substring(0,2).toInt(16)){
                "Code mismatch: re-check the code on TV"
            }
            socket.send(Pb().n(1,2).n(2,200).m(40,Pb().b(1,digest)).bytes())
            val reply=socket.read()
            require(reply.has(41)&&reply.n(2)==200L){"TV rejected pairing code"}
            prefs().edit().putString("last_tv_ip",host).putString(pinsPrefix+host,hex(sha256(socket.certificate.encoded))).apply()
            paired=true;phase="paired";lastError=""
            return "Native Wi-Fi remote paired successfully"
        } catch(e:Exception) {phase="error";lastError=e.message?:"Pairing failed";throw e}
        finally{closePair()}
    }
    private fun closePair(){pairSocket?.close();pairSocket=null}
    @Synchronized fun connect(ip:String?=null):String {
        val candidate=validHost(if(ip.isNullOrBlank())host else ip)
        if(candidate!=host){disconnect();host=candidate;paired=prefs().contains(pinsPrefix+candidate)}
        require(prefs().contains(pinsPrefix+host)){"Pair this TV using the code displayed on its screen first"}
        if(keys?.ready==true)return "Already connected"
        keys?.close()
        val pin=prefs().getString(pinsPrefix+host,null)?:error("TV not paired")
        val session=RemoteSession(TvSocket(identity(),host,6466,pin),false)
        session.start()
        keys=session;phase="connected";lastError=""
        prefs().edit().putString("last_tv_ip",host).apply()
        return "TV connected over native Wi-Fi Remote v2"
    }
    @Synchronized fun key(name:String):String {
        val code=mapOf("HOME" to 3,"BACK" to 4,"UP" to 19,"DOWN" to 20,
            "LEFT" to 21,"RIGHT" to 22,"OK" to 23,"VOL_UP" to 24,"VOL_DOWN" to 25,
            "POWER" to 26,"MENU" to 82,"PLAY_PAUSE" to 85,"MUTE" to 164,
            "SEARCH" to 84)[name]?:throw IllegalArgumentException("Unsupported remote key")
        connect()
        keys!!.key(code)
        return "Wi-Fi remote command sent ($name)"
    }
    @Synchronized fun text(text:String,live:Boolean):String {
        require(text.isNotEmpty()&&text.length<=12000){"Text length must be 1–12000 characters"}
        require(host.isNotBlank()&&prefs().contains(pinsPrefix+host)){"Pair your TV first"}
        if(input?.ready!=true){
            input?.close()
            input=RemoteSession(TvSocket(identity(),host,6466,prefs().getString(pinsPrefix+host,null)),true).also{it.start()}
        }
        input!!.text(text)
        if(!live) {
            // The remote service processes IME edits asynchronously: let the frame drain,
            // then release its temporary IME so TV restores ALRADHY-KB.
            Thread.sleep(380)
            endInput()
        }
        return if(live)"Native Wi-Fi text sent; keyboard will restore after inactivity" else
            "Text sent over native Wi-Fi; temporary input session closed"
    }
    @Synchronized fun endInput() {input?.close();input=null}
    @Synchronized fun disconnect() {
        endInput();keys?.close();keys=null;closePair()
        phase="disconnected"
    }
    @Synchronized fun tick() {
        if(input!=null && System.currentTimeMillis()-(input?.lastInput?:0)>4000)endInput()
    }
    @Synchronized fun status():JSONObject {
        return JSONObject().put("host",host).put("paired",paired)
            .put("connected",keys?.ready==true)
            .put("nativeImeActive",input?.ready==true)
            .put("phase",phase).put("lastError",lastError)
    }
    @Synchronized fun scan():String {
        val context=context()
        if(serviceListener!=null)return "Scanning already active"
        val manager=context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd=manager
        val listener=object:NsdManager.DiscoveryListener{
            override fun onDiscoveryStarted(type:String){}
            override fun onServiceFound(service:NsdServiceInfo) {
                if(!service.serviceType.contains("androidtvremote"))return
                try{manager.resolveService(service,object:NsdManager.ResolveListener{
                    override fun onResolveFailed(info:NsdServiceInfo,errorCode:Int){}
                    override fun onServiceResolved(info:NsdServiceInfo){
                        val ip=info.host?.hostAddress?:return
                        synchronized(found){found[info.serviceName]=ip}
                    }
                })}catch(_:Exception){}
            }
            override fun onServiceLost(service:NsdServiceInfo){}
            override fun onStartDiscoveryFailed(type:String,errorCode:Int){
                serviceListener=null;runCatching{manager.stopServiceDiscovery(this)}
            }
            override fun onStopDiscoveryFailed(type:String,errorCode:Int){serviceListener=null}
            override fun onDiscoveryStopped(type:String){serviceListener=null}
        }
        manager.discoverServices("_androidtvremote2._tcp.",NsdManager.PROTOCOL_DNS_SD,listener)
        serviceListener=listener
        return "Scanning for Android TV Remote Service"
    }
    fun discoveries():JSONArray{
        val arr=JSONArray()
        synchronized(found){for((name,ip) in found){arr.put(JSONObject().put("name",name).put("ip",ip))}}
        return arr
    }
}
