/*
 * This Kotlin source file was generated by the Gradle 'init' task.
 */
package org.lightningdevkit.ldknode

import kotlin.UInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.io.path.createTempDirectory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

import org.lightningdevkit.ldknode.*;

fun runCommandAndWait(cmd: String): String {
    println("Running command \"$cmd\"")
    val p = Runtime.getRuntime().exec(cmd)
    p.waitFor()
    val stdout = p.inputStream.bufferedReader().lineSequence().joinToString("\n")
    val stderr = p.errorStream.bufferedReader().lineSequence().joinToString("\n")
    return stdout + stderr
}

fun mine(blocks: UInt) {
    val address = runCommandAndWait("bitcoin-cli -regtest getnewaddress")
    val output = runCommandAndWait("bitcoin-cli -regtest generatetoaddress $blocks $address")
    println("Mining output: $output")
}

fun sendToAddress(address: String, amountSats: UInt): String {
    val amountBtc = amountSats.toDouble() / 100000000.0
    val output = runCommandAndWait("bitcoin-cli -regtest sendtoaddress $address $amountBtc")
    return output
}

fun setup() {
    runCommandAndWait("bitcoin-cli -regtest createwallet ldk_node_test")
    runCommandAndWait("bitcoin-cli -regtest loadwallet ldk_node_test true")
    mine(101u)
}

fun waitForTx(esploraEndpoint: String, txid: String) {
    var esploraPickedUpTx = false
    val re = Regex("\"txid\":\"$txid\"");
    while (!esploraPickedUpTx) {
        val client = HttpClient.newBuilder().build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(esploraEndpoint + "/tx/" + txid))
            .build();

        val response = client.send(request, HttpResponse.BodyHandlers.ofString());

        esploraPickedUpTx = re.containsMatchIn(response.body());
        Thread.sleep(1_000)
    }
}

class LibraryTest {
    @Test fun fullCycle() {
        setup()

        val network: Network = "regtest"
        assertEquals(network, "regtest")

        val tmpDir1 = createTempDirectory("ldk_node").toString()
        println("Random dir 1: $tmpDir1")
        val tmpDir2 = createTempDirectory("ldk_node").toString()
        println("Random dir 2: $tmpDir2")

        val listenAddress1 = "127.0.0.1:2323"
        val listenAddress2 = "127.0.0.1:2324"

        val esploraEndpoint = "http://127.0.0.1:3002"

        val config1 = Config(tmpDir1, esploraEndpoint, network, listenAddress1, 2048u)
        val config2 = Config(tmpDir2, esploraEndpoint, network, listenAddress2, 2048u)

        val builder1 = Builder.fromConfig(config1)
        val builder2 = Builder.fromConfig(config2)

        val node1 = builder1.build()
        val node2 = builder2.build()

        node1.start()
        node2.start()

        val nodeId1 = node1.nodeId()
        println("Node Id 1: $nodeId1")

        val nodeId2 = node2.nodeId()
        println("Node Id 2: $nodeId2")

        val address1 = node1.newFundingAddress()
        println("Funding address 1: $address1")

        val address2 = node2.newFundingAddress()
        println("Funding address 2: $address2")

        val txid1 = sendToAddress(address1, 100000u)
        val txid2 = sendToAddress(address2, 100000u)
        mine(6u)

        waitForTx(esploraEndpoint, txid1)
        waitForTx(esploraEndpoint, txid2)

        node1.syncWallets()
        node2.syncWallets()

        val spendableBalance1 = node1.spendableOnchainBalanceSats()
        val spendableBalance2 = node2.spendableOnchainBalanceSats()
        val totalBalance1 = node1.totalOnchainBalanceSats()
        val totalBalance2 = node2.totalOnchainBalanceSats()
        println("Spendable balance 1: $spendableBalance1")
        println("Spendable balance 2: $spendableBalance1")
        println("Total balance 1: $totalBalance1")
        println("Total balance 2: $totalBalance1")
        assertEquals(100000u, spendableBalance1)
        assertEquals(100000u, spendableBalance2)
        assertEquals(100000u, totalBalance1)
        assertEquals(100000u, totalBalance2)

        node1.connectOpenChannel(nodeId2, listenAddress2, 50000u, null, true)

        val channelPendingEvent1 = node1.nextEvent()
        println("Got event: $channelPendingEvent1")
        assert(channelPendingEvent1 is Event.ChannelPending)
        node1.eventHandled()

        val channelPendingEvent2 = node2.nextEvent()
        println("Got event: $channelPendingEvent2")
        assert(channelPendingEvent2 is Event.ChannelPending)
        node2.eventHandled()

        val fundingTxid = when (channelPendingEvent1) {
            is Event.ChannelPending -> channelPendingEvent1.fundingTxo.txid
                else -> return
        }

        waitForTx(esploraEndpoint, fundingTxid)

        mine(6u)

        node1.syncWallets()
        node2.syncWallets()

        val spendableBalance1AfterOpen = node1.spendableOnchainBalanceSats()
        val spendableBalance2AfterOpen = node2.spendableOnchainBalanceSats()
        println("Spendable balance 1 after open: $spendableBalance1AfterOpen")
        println("Spendable balance 2 after open: $spendableBalance2AfterOpen")
        assert(spendableBalance1AfterOpen > 49000u)
        assert(spendableBalance1AfterOpen < 50000u)
        assertEquals(100000u, spendableBalance2AfterOpen)

        val channelReadyEvent1 = node1.nextEvent()
        println("Got event: $channelReadyEvent1")
        assert(channelReadyEvent1 is Event.ChannelReady)
        node1.eventHandled()

        val channelReadyEvent2 = node2.nextEvent()
        println("Got event: $channelReadyEvent2")
        assert(channelReadyEvent2 is Event.ChannelReady)
        node2.eventHandled()

        val channelId = when (channelReadyEvent2) {
            is Event.ChannelReady -> channelReadyEvent2.channelId
                else -> return
        }

        val invoice = node2.receivePayment(1000000u, "asdf", 9217u)

        node1.sendPayment(invoice)

        val paymentSuccessfulEvent = node1.nextEvent()
        println("Got event: $paymentSuccessfulEvent")
        assert(paymentSuccessfulEvent is Event.PaymentSuccessful)
        node1.eventHandled()

        val paymentReceivedEvent = node2.nextEvent()
        println("Got event: $paymentReceivedEvent")
        assert(paymentReceivedEvent is Event.PaymentReceived)
        node2.eventHandled()

        node2.closeChannel(channelId, nodeId1)

        val channelClosedEvent1 = node1.nextEvent()
        println("Got event: $channelClosedEvent1")
        assert(channelClosedEvent1 is Event.ChannelClosed)
        node1.eventHandled()

        val channelClosedEvent2 = node2.nextEvent()
        println("Got event: $channelClosedEvent2")
        assert(channelClosedEvent2 is Event.ChannelClosed)
        node2.eventHandled()

        mine(1u)

        // Sleep a bit to allow for the block to propagate to esplora
        Thread.sleep(3_000)

        node1.syncWallets()
        node2.syncWallets()

        val spendableBalance1AfterClose = node1.spendableOnchainBalanceSats()
        val spendableBalance2AfterClose = node2.spendableOnchainBalanceSats()
        println("Spendable balance 1 after close: $spendableBalance1AfterClose")
        println("Spendable balance 2 after close: $spendableBalance2AfterClose")
        assert(spendableBalance1AfterClose > 95000u)
        assert(spendableBalance1AfterClose < 100000u)
        assertEquals(101000u, spendableBalance2AfterClose)

        node1.stop()
        node2.stop()
    }
}
