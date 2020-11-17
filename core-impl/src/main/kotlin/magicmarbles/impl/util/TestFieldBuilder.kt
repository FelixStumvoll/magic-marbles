package magicmarbles.impl.util

import magicmarbles.api.field.*

class TestFieldBuilder(private val fieldProvider: ModifiableFieldFactory) : FieldBuilder {
    override fun build(width: Int, height: Int): PlayableField {
        return fieldProvider.createEmptyField(width, height).apply {
            map { column, _ ->
                when (column) {
                    0 -> Marble(Color.RED)
                    1 -> Marble(Color.BLUE)
                    2 -> Marble(Color.GREEN)
                    else -> Marble(Color.randomColor())
                }
            }
        }
    }
}