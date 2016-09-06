package scorex.core

import akka.actor.Actor
import scorex.core.api.http.ApiRoute
import scorex.core.consensus.History
import scorex.core.network.NodeViewSynchronizer
import scorex.core.network.NodeViewSynchronizer._
import scorex.core.transaction.NodeStateModifier.ModifierTypeId
import scorex.core.transaction._
import scorex.core.transaction.box.proposition.Proposition
import scorex.core.transaction.state.MinimalState
import scorex.core.transaction.wallet.Wallet

trait NodeViewComponent {
  self =>

  type NVCT >: self.type <: NodeViewComponent

  def companion: NodeViewComponentCompanion
}

trait NodeViewComponentCompanion {

  def api: ApiRoute

  def produceModification[M <: NodeStateModifier, CompType <: NodeViewComponent](component: CompType, m: M): UndoneModification[M, CompType]

  //network functions to call
}


//todo: listeners
//todo: async update?

/**
  * Composite local view
  *
  * @tparam P
  * @tparam TX
  */
/*
 (S, H, MP, W)
 - process state modifier M in following order (H -> S -> MP -> W)

 - HxM -> Outcome[H]
 */
trait NodeViewHolder[P <: Proposition, TX <: Transaction[P, TX]] extends Actor {

  type PMOD <: PersistentNodeStateModifier

  type HIS <: History[P, TX, PMOD]
  type MS <: MinimalState[P, TX]
  type WL <: Wallet[P, TX]
  type MP <: MemoryPool[TX]

  type NodeState = (HIS, MS, WL, MP)

  val companions: Map[ModifierTypeId, NodeStateModifierCompanion[_ <: NodeStateModifier]]

  val networkChunkSize = 100 //todo: fix

  def restoreState(): Option[NodeState]

  private var nodeState: NodeState = restoreState().getOrElse(genesisState)

  def history(): HIS = nodeState._1

  def minimalState(): MS = nodeState._2

  def wallet(): WL = nodeState._3

  def memoryPool(): MP = nodeState._4

  lazy val historyCompanion = history().companion

  def modify[MOD <: NodeStateModifier](m: MOD) = {
    val modification = historyCompanion.produceModification(history(), m)
    val hd = modification.process()
    val md = hd.flatMap[MS](minimalState())
    val wld = md.flatMap[WL](wallet())
    val mpd = wld.flatMap[MP](memoryPool())
  }

  protected def genesisState: NodeState

  def apis: Seq[ApiRoute] = Seq(
    genesisState._1,
    genesisState._2,
    genesisState._3,
    genesisState._4
  ).map(_.companion.api)

  override def receive: Receive =
    compareViews orElse
      readLocalObjects orElse
      processRemoteObjects

  def processRemoteObjects: Receive = {
    case ModifiersFromRemote(sid, modifierTypeId, remoteObjects) =>
      val companion = companions(modifierTypeId)

      val parsed = remoteObjects.map(companion.parse).map(_.get)

      parsed foreach modify
  }

  def compareViews: Receive = {
    case CompareViews(sid, modifierTypeId, modifierIds) =>
      val ids = modifierTypeId match {
        case typeId: Byte if typeId == Transaction.TransactionModifierId =>
          memoryPool().notIn(modifierIds)
        case typeId: Byte =>
          history().continuationIds(modifierIds, networkChunkSize)
      }

      sender() ! NodeViewSynchronizer.RequestFromLocal(sid, modifierTypeId, ids)
  }

  def readLocalObjects: Receive = {
    case GetLocalObjects(sid, modifierTypeId, modifierIds) =>
      val objs: Seq[NodeStateModifier] = modifierTypeId match {
        case typeId: Byte if typeId == Transaction.TransactionModifierId =>
          memoryPool().getAll(modifierIds)
        case typeId: Byte =>
          modifierIds.flatMap(id => history().blockById(id))
      }
      sender() ! NodeViewSynchronizer.ResponseFromLocal(sid, modifierTypeId, objs)
  }
}