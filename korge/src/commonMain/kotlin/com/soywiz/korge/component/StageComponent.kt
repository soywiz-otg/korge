package com.soywiz.korge.component

import com.soywiz.kds.*
import com.soywiz.kds.iterators.*
import com.soywiz.korge.baseview.*
import com.soywiz.korge.view.*

/**
 * **Important**: To use this component you have to call the [Views.registerStageComponent] extension method at the start of the APP.
 *
 * Component with [added] and [removed] methods that are executed
 * once the view is going to be displayed, and when the view has been removed
 */
interface StageComponent : Component {
    fun added(views: Views)
    fun removed(views: Views)
}

fun <T : View> T.onAttachDetach(views: Views, onAttach: Views.(T) -> Unit = {}, onDetach: Views.(T) -> Unit = {}): T {
    val view = this
    views.registerStageComponent()
    view.addComponent(object : StageComponent {
        override val view: BaseView get() = view
        override fun added(views: Views) {
            onAttach(views, view)
        }
        override fun removed(views: Views) {
            onDetach(views, view)
        }
    })
    return this
}

/**
 * Enables the use of [StageComponent] components.
 */
fun Views.registerStageComponent() {
    val EXTRA_ID = "Views.registerStageComponent"
    if (views.getExtra(EXTRA_ID) == true) return
    views.setExtra(EXTRA_ID, true)
    val componentsInStagePrev = FastArrayList<StageComponent>()
    val componentsInStageCur = linkedSetOf<StageComponent>()
    val componentsInStage = linkedSetOf<StageComponent>()
    val tempViews: FastArrayList<View> = FastArrayList()
    onBeforeRender {
        componentsInStagePrev.clear()
        componentsInStagePrev += componentsInStageCur
        componentsInStageCur.clear()

        val stagedViews = getAllDescendantViews(stage, tempViews)

        stagedViews.fastForEach { view ->
            view._components?.eother?.fastForEach {
                if (it is StageComponent) {
                    componentsInStageCur += it
                    if (it !in componentsInStage) {
                        componentsInStage += it
                        it.added(views)
                    }
                }
            }
        }

        componentsInStagePrev.fastForEach {
            if (it !in componentsInStageCur) {
                it.removed(views)
            }
        }
    }
}
