package table

import model.MmoBattle
import model.MmoBattleActor
import model.MmoBattleActorTableImpl
import model.MmoBattleCommand
import model.MmoBattleCommandTableImpl
import model.MmoBattleEvent
import model.MmoBattleEventTableImpl
import model.MmoBattleLease
import model.MmoBattleLeaseTableImpl
import model.MmoBattleSlot
import model.MmoBattleSlotTableImpl
import model.MmoBattleTableImpl
import model.MmoBattleTransition
import model.MmoBattleTransitionTableImpl
import model.MmoRewardSettlement
import model.MmoRewardSettlementTableImpl
import neton.database.api.Table

/** 战斗各表的访问 Facade；实现由 KSP EntityTableProcessor 生成。 */
object MmoBattleTable : Table<MmoBattle, Long> by MmoBattleTableImpl
object MmoBattleTransitionTable : Table<MmoBattleTransition, Long> by MmoBattleTransitionTableImpl
object MmoBattleActorTable : Table<MmoBattleActor, Long> by MmoBattleActorTableImpl
object MmoBattleSlotTable : Table<MmoBattleSlot, Long> by MmoBattleSlotTableImpl
object MmoBattleCommandTable : Table<MmoBattleCommand, Long> by MmoBattleCommandTableImpl
object MmoBattleEventTable : Table<MmoBattleEvent, Long> by MmoBattleEventTableImpl
object MmoBattleLeaseTable : Table<MmoBattleLease, Long> by MmoBattleLeaseTableImpl
object MmoRewardSettlementTable : Table<MmoRewardSettlement, Long> by MmoRewardSettlementTableImpl
