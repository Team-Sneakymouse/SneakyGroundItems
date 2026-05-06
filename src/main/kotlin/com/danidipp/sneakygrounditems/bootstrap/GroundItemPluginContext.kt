package com.danidipp.sneakygrounditems.bootstrap

import com.danidipp.sneakygrounditems.SneakyGroundItems
import com.danidipp.sneakygrounditems.commerce.BankService
import com.danidipp.sneakygrounditems.commerce.GroundItemCommerceService
import com.danidipp.sneakygrounditems.display.GroundItemDisplayService
import com.danidipp.sneakygrounditems.display.PlacementService
import com.danidipp.sneakygrounditems.integration.coreprotect.GroundItemAuditService
import com.danidipp.sneakygrounditems.integration.magicspells.GroundItemMetadataService
import com.danidipp.sneakygrounditems.interaction.GroundItemEffectsService
import com.danidipp.sneakygrounditems.interaction.GroundItemInteractionService
import com.danidipp.sneakygrounditems.interaction.GroundItemInventoryService
import com.danidipp.sneakygrounditems.interaction.GroundItemLookupService
import com.danidipp.sneakygrounditems.lock.LockService
import com.danidipp.sneakygrounditems.migration.GroundItemMigrationService
import com.danidipp.sneakygrounditems.persistence.PersistenceService
import com.danidipp.sneakygrounditems.runtime.GroundItemRuntimeStateService

internal data class GroundItemPluginContext(
    val persistence: PersistenceService,
    val lockService: LockService,
    val runtimeState: GroundItemRuntimeStateService,
    val metadataService: GroundItemMetadataService,
    val auditService: GroundItemAuditService,
    val inventoryService: GroundItemInventoryService,
    val effectsService: GroundItemEffectsService,
    val placementService: PlacementService,
    val lookupService: GroundItemLookupService,
    val displayService: GroundItemDisplayService,
    val migrationService: GroundItemMigrationService,
    val commerceService: GroundItemCommerceService,
    val interactionService: GroundItemInteractionService,
) {
    companion object {
        fun create(plugin: SneakyGroundItems): GroundItemPluginContext {
            val persistence = PersistenceService(plugin)
            val lockService = LockService(persistence)
            val runtimeState = GroundItemRuntimeStateService()
            val metadataService = GroundItemMetadataService()
            val auditService = GroundItemAuditService()
            val inventoryService = GroundItemInventoryService()
            val effectsService = GroundItemEffectsService(runtimeState)
            val placementService = PlacementService(persistence, lockService)
            val lookupService = GroundItemLookupService(persistence, lockService, placementService, runtimeState)
            val displayService = GroundItemDisplayService(persistence, lockService, placementService, metadataService)
            val migrationService = GroundItemMigrationService(plugin, persistence)
            val commerceService = GroundItemCommerceService(
                persistence = persistence,
                lockService = lockService,
                placementService = placementService,
                displayService = displayService,
                bankService = BankService(),
                metadataService = metadataService,
                lookupService = lookupService,
                runtimeState = runtimeState,
                inventoryService = inventoryService,
                effectsService = effectsService,
                auditService = auditService,
            )
            val interactionService = GroundItemInteractionService(
                plugin = plugin,
                persistence = persistence,
                lockService = lockService,
                placementService = placementService,
                displayService = displayService,
                commerceService = commerceService,
                metadataService = metadataService,
                lookupService = lookupService,
                runtimeState = runtimeState,
                effectsService = effectsService,
                inventoryService = inventoryService,
                auditService = auditService,
            )

            return GroundItemPluginContext(
                persistence = persistence,
                lockService = lockService,
                runtimeState = runtimeState,
                metadataService = metadataService,
                auditService = auditService,
                inventoryService = inventoryService,
                effectsService = effectsService,
                placementService = placementService,
                lookupService = lookupService,
                displayService = displayService,
                migrationService = migrationService,
                commerceService = commerceService,
                interactionService = interactionService,
            )
        }
    }
}
