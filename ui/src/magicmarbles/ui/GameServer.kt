package magicmarbles.ui

import com.github.michaelbull.result.*
import magicmarbles.api.game.Game
import magicmarbles.api.game.GameFactory
import magicmarbles.api.impl.settings.ExtendedSettings
import magicmarbles.api.impl.settings.factory.ExtendedSettingsFactory
import magicmarbles.ui.dto.SyncDto
import magicmarbles.ui.dto.game.*
import magicmarbles.ui.dto.settings.SettingsDto
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class GameServer(
    private val gameFactory: GameFactory<ExtendedSettings>,
    private val settingsFactory: ExtendedSettingsFactory,
    private val defaultSettings: ExtendedSettings
) {
    data class PlayerState(
        val game: Game,
        val settings: ExtendedSettings,
        var uniqueStateId: Int,
        var lastSeen: LocalDateTime
    )

    private val activeGames = ConcurrentHashMap<String, PlayerState>()

    fun clearOldStates() {
        val clearLimit = LocalDateTime.now().minusHours(1)
        activeGames.values.removeIf { it.lastSeen.isBefore(clearLimit) }
    }

    fun sync(id: String): SyncDto =
        getPlayerState(id)
            .onSuccess { updateLastSeen(it) }
            .map { SyncDto(it.settings.toDto(), it.toGameStateDto()) }
            .recover { SyncDto(defaultSettings.toDto(), null) }
            .value

    fun startWithConfiguration(
        id: String,
        settingsDto: SettingsDto
    ): Result<GameStateDto, MarbleGameException> {
        val settings = settingsDto.toSettings()
        return gameFactory.createGame(settings)
            .map { game ->
                PlayerState(game, settings, getUniqueId(), LocalDateTime.now()).also {
                    activeGames[id] = it
                }
            }
            .mapEither({ it.toGameStateDto() }, { WrappedSettingsException(it) })
    }

    fun restartGame(id: String): Result<GameStateDto, NoGameException> =
        getPlayerState(id)
            .onSuccess {
                updateLastSeen(it)
                it.game.restart()
                it.uniqueStateId = getUniqueId()
            }
            .map {
                it.toGameStateDto()
            }

    fun move(id: String, moveRequest: MoveRequestDto): Result<GameStateDto, MarbleGameException> =
        getPlayerState(id)
            .onSuccess { updateLastSeen(it) }
            .flatMap { it.validateStateId(moveRequest.stateId) }
            .flatMap {
                it.game.move(moveRequest.coordinates.column, moveRequest.coordinates.row)
                    .map { _ -> it }
                    .mapError { err ->
                        WrappedGameException(
                            err
                        )
                    }
            }
            .onSuccess { it.uniqueStateId = getUniqueId() }
            .map { it.toGameStateDto() }

    fun hover(id: String, hoverRequest: MoveRequestDto): Result<HoverResultDto, MarbleGameException> =
        getPlayerState(id)
            .onSuccess { updateLastSeen(it) }
            .flatMap { it.validateStateId(hoverRequest.stateId) }
            .flatMap {
                it.game.field.getConnectedMarbles(hoverRequest.coordinates.column, hoverRequest.coordinates.row)
                    .mapError { err -> WrappedFieldException(err) }
            }
            .map { HoverResultDto(it.map { marble -> CoordinateDto(marble.first, marble.second) }) }

    private fun PlayerState.validateStateId(stateId: Int): Result<PlayerState, OutdatedStateException> =
        if (stateId == this.uniqueStateId) Ok(this)
        else Err(
            OutdatedStateException(
                if (this.game.over) SyncDto(this.settings.toDto(), null)
                else SyncDto(null, this.toGameStateDto())
            )
        )

    private fun SettingsDto.toSettings(): ExtendedSettings =
        settingsFactory.build(
            width,
            height,
            minimumConnectedMarbles,
            defaultSettings.pointCalculation,
            remainingMarblePenalty
        )

    private fun ExtendedSettings.toDto(): SettingsDto =
        SettingsDto(width, height, minConnectedMarbles, remainingMarblePenalty)

    private fun PlayerState.toGameStateDto(): GameStateDto {
        val colorList = this.game.field.field
            .map { column ->
                column.map { it?.color?.hex?.let { color -> MarbleDto(color) } }
            }
        return GameStateDto(colorList, this.game.points, this.game.over, this.uniqueStateId)
    }

    private fun getUniqueId() = UUID.randomUUID().hashCode()

    private fun getPlayerState(id: String): Result<PlayerState, NoGameException> =
        activeGames[id].let {
            if (it == null) Err(NoGameException())
            else Ok(it)
        }

    private fun updateLastSeen(playerState: PlayerState) {
        playerState.lastSeen = LocalDateTime.now()
    }
}