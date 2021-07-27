package com.github.earthcomputer.classfileindexer.services

import com.github.earthcomputer.classfileindexer.MyBundle
import com.intellij.openapi.project.Project

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
