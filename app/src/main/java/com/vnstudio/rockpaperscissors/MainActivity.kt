package com.vnstudio.rockpaperscissors

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.vnstudio.rockpaperscissors.databinding.ActivityMainBinding
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Random

class MainActivity : AppCompatActivity() {

    private val STRATEGY = Strategy.P2P_CLUSTER
    private lateinit var connectionsClient: ConnectionsClient
    private val REQUEST_CODE_REQUIRED_PERMISSIONS = 1
    private var opponentName: String? = null
    private var opponentEndpointId: String? = null
    private var opponentScore = 0
    private var opponentChoice: GameChoice? = null
    private var myCodeName: String = CodenameGenerator.generate()
    private var myScore = 0
    private var myChoice: GameChoice? = null
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        connectionsClient = Nearby.getConnectionsClient(this)

        binding.myName.text = "You\n($myCodeName)"
        binding.findOpponent.setOnClickListener {
            startAdvertising()
            startDiscovery()
            binding.status.text = "Searching for opponents..."
            binding.findOpponent.visibility = View.GONE
            binding.disconnect.visibility = View.VISIBLE
        }
        binding.apply {
            rock.setOnClickListener { sendGameChoice(GameChoice.ROCK) }
            paper.setOnClickListener { sendGameChoice(GameChoice.PAPER) }
            scissors.setOnClickListener { sendGameChoice(GameChoice.SCISSORS) }
        }
        binding.disconnect.setOnClickListener {
            opponentEndpointId?.let { connectionsClient.disconnectFromEndpoint(it) }
            resetGame()
        }

        resetGame()
    }

    @CallSuper
    override fun onStart() {
        super.onStart()
        val permissionsList = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.NFC
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsList.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsList.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissionsList.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsList.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        permissionsList.removeIf {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (permissionsList.isNotEmpty()) {
            requestPermissions(
                permissionsList.toTypedArray(),
                REQUEST_CODE_REQUIRED_PERMISSIONS
            )
        }
    }

    @CallSuper
    override fun onStop() {
        connectionsClient.apply {
            stopAdvertising()
            stopDiscovery()
            stopAllEndpoints()
        }
        resetGame()
        super.onStop()
    }

    @CallSuper
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val errMsg = "Cannot start without required permissions"
        if (requestCode == REQUEST_CODE_REQUIRED_PERMISSIONS) {
            grantResults.forEach {
                if (it == PackageManager.PERMISSION_DENIED) {
                    Toast.makeText(this, errMsg, Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
            }
            recreate()
        }
    }

    private fun sendGameChoice(choice: GameChoice) {
        myChoice = choice
        connectionsClient.sendPayload(
            opponentEndpointId!!,
            Payload.fromBytes(choice.name.toByteArray(UTF_8))
        )
        binding.status.text = "You chose ${choice.name}"
        setGameControllerEnabled(false)
    }

    private fun setGameControllerEnabled(state: Boolean) {
        binding.apply {
            rock.isEnabled = state
            paper.isEnabled = state
            scissors.isEnabled = state
        }
    }

    private val payloadCallback: PayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let {
                opponentChoice = GameChoice.valueOf(String(it, UTF_8))
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS
                && myChoice != null && opponentChoice != null
            ) {
                val mc = myChoice!!
                val oc = opponentChoice!!
                when {
                    mc.beats(oc) -> {
                        binding.status.text = "${mc.name} beats ${oc.name}"
                        myScore++
                    }

                    mc == oc -> {
                        binding.status.text = "You both chose ${mc.name}"
                    }

                    else -> {
                        binding.status.text = "${mc.name} loses to ${oc.name}"
                        opponentScore++
                    }
                }
                binding.score.text = "$myScore : $opponentScore"
                myChoice = null
                opponentChoice = null
                setGameControllerEnabled(true)
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            opponentName = "Opponent\n(${info.endpointName})"
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectionsClient.stopAdvertising()
                connectionsClient.stopDiscovery()
                opponentEndpointId = endpointId
                binding.opponentName.text = opponentName
                binding.status.text = "Connected"
                setGameControllerEnabled(true)
            }
        }

        override fun onDisconnected(endpointId: String) {
            resetGame()
        }
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            myCodeName,
            packageName,
            connectionLifecycleCallback,
            options
        ).addOnFailureListener { e ->
            Toast.makeText(this, e.localizedMessage, Toast.LENGTH_LONG).show()
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            connectionsClient.requestConnection(myCodeName, endpointId, connectionLifecycleCallback).addOnFailureListener { e ->
                Toast.makeText(this@MainActivity, e.localizedMessage, Toast.LENGTH_LONG).show()
            }
        }

        override fun onEndpointLost(endpointId: String) {
        }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(packageName, endpointDiscoveryCallback, options).addOnFailureListener { e ->
            Toast.makeText(this@MainActivity, e.localizedMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetGame() {
        opponentEndpointId = null
        opponentName = null
        opponentChoice = null
        opponentScore = 0
        myChoice = null
        myScore = 0
        binding.disconnect.visibility = View.GONE
        binding.findOpponent.visibility = View.VISIBLE
        setGameControllerEnabled(false)
        binding.opponentName.text = "opponent\n(none yet)"
        binding.status.text = "..."
        binding.score.text = ":"
    }

    private enum class GameChoice {
        ROCK, PAPER, SCISSORS;

        fun beats(other: GameChoice): Boolean =
            (this == ROCK && other == SCISSORS)
                    || (this == SCISSORS && other == PAPER)
                    || (this == PAPER && other == ROCK)
    }

    internal object CodenameGenerator {
        private val COLORS = arrayOf(
            "Red", "Orange", "Yellow", "Green", "Blue", "Indigo", "Violet", "Purple", "Lavender"
        )
        private val TREATS = arrayOf(
            "Cupcake", "Donut", "Eclair", "Froyo", "Gingerbread", "Honeycomb",
            "Ice Cream Sandwich", "Jellybean", "Kit Kat", "Lollipop", "Marshmallow", "Nougat",
            "Oreo", "Pie"
        )
        private val generator = Random()

        fun generate(): String {
            val color = COLORS[generator.nextInt(COLORS.size)]
            val treat = TREATS[generator.nextInt(TREATS.size)]
            return "$color $treat"
        }
    }
}