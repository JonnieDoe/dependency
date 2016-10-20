package db

import com.bryzek.dependency.actors.MainActor
import com.bryzek.dependency.api.lib.Version
import com.bryzek.dependency.v0.models.{Library, ProjectLibrary, SyncEvent, VersionForm}
import io.flow.postgresql.{Query, OrderBy, Pager}
import io.flow.common.v0.models.UserReference
import anorm._
import play.api.db._
import play.api.Play.current
import play.api.libs.json._

case class ProjectLibraryForm(
  projectId: String,
  groupId: String,
  artifactId: String,
  version: VersionForm,
  path: String
)

object ProjectLibrariesDao {

  private[this] val BaseQuery = Query(s"""
    select project_libraries.id,
           project_libraries.group_id,
           project_libraries.artifact_id,
           project_libraries.version,
           project_libraries.cross_build_version,
           project_libraries.path,
           project_libraries.library_id as library_id,
           projects.id as project_id,
           projects.name as project_name,
           organizations.id as project_organization_id,
           organizations.key as project_organization_key
      from project_libraries
      join projects on projects.id = project_libraries.project_id
      join organizations on organizations.id = projects.organization_id
  """)

  private[this] val InsertQuery = """
    insert into project_libraries
    (id, project_id, group_id, artifact_id, version, cross_build_version, path, updated_by_user_id)
    values
    ({id}, {project_id}, {group_id}, {artifact_id}, {version}, {cross_build_version}, {path}, {updated_by_user_id})
  """

  private[this] val SetLibraryQuery = """
    update project_libraries
       set library_id = {library_id},
           updated_by_user_id = {updated_by_user_id}
     where id = {id}
  """

  private[this] val RemoveLibraryQuery = """
    update project_libraries
       set library_id = null,
           updated_by_user_id = {updated_by_user_id}
     where id = {id}
  """

  private[db] def validate(
    user: UserReference,
    form: ProjectLibraryForm
  ): Seq[String] = {
    val groupIdErrors = if (form.groupId.trim.isEmpty) {
      Seq("Group ID cannot be empty")
    } else {
      Nil
    }

    val artifactIdErrors = if (form.artifactId.trim.isEmpty) {
      Seq("Artifact ID cannot be empty")
    } else {
      Nil
    }

    val versionErrors = if (form.version.version.trim.isEmpty) {
      Seq("Version cannot be empty")
    } else {
      Nil
    }

    val projectErrors = ProjectsDao.findById(Authorization.All, form.projectId) match {
      case None => Seq("Project not found")
      case Some(project) => {
        MembershipsDao.isMemberByOrgId(project.organization.id, user) match {
          case false => Seq("You are not authorized to edit this project")
          case true => Nil
        }
      }
    }

    val existsErrors = if (Seq(groupIdErrors, artifactIdErrors, versionErrors, projectErrors).flatten.isEmpty) {
      ProjectLibrariesDao.findByProjectIdAndGroupIdAndArtifactIdAndVersion(
        Authorization.All, form.projectId, form.groupId, form.artifactId, form.version
      ) match {
        case None => Nil
        case Some(lib) => {
          Seq("Project library with this group id, artifact id, and version already exists")
        }
      }
    } else {
      Nil
    }

    projectErrors ++ groupIdErrors ++ artifactIdErrors ++ versionErrors ++ existsErrors
  }

  def upsert(createdBy: UserReference, form: ProjectLibraryForm): Either[Seq[String], ProjectLibrary] = {
    ProjectLibrariesDao.findByProjectIdAndGroupIdAndArtifactIdAndVersion(
      Authorization.All, form.projectId, form.groupId, form.artifactId, form.version
    ) match {
      case None => {
        create(createdBy, form)
      }
      case Some(lib) => {
        Right(lib)
      }
    }
  }

  def create(createdBy: UserReference, form: ProjectLibraryForm): Either[Seq[String], ProjectLibrary] = {
    validate(createdBy, form) match {
      case Nil => {
        val id = io.flow.play.util.IdGenerator("prl").randomId()

        DB.withConnection { implicit c =>
          SQL(InsertQuery).on(
            'id -> id,
            'project_id -> form.projectId,
            'group_id -> form.groupId.trim,
            'artifact_id -> form.artifactId.trim,
            'version -> form.version.version.trim,
            'cross_build_version -> Util.trimmedString(form.version.crossBuildVersion),
            'path -> form.path.trim,
            'updated_by_user_id -> createdBy.id
          ).execute()
          MainActor.ref ! MainActor.Messages.ProjectLibraryCreated(form.projectId, id)
        }

        Right(
          findById(Authorization.All, id).getOrElse {
            sys.error("Failed to create project library")
          }
        )
      }
      case errors => Left(errors)
    }
  }

  def removeLibrary(user: UserReference, projectLibrary: ProjectLibrary) {
    DB.withConnection { implicit c =>
      SQL(RemoveLibraryQuery).on(
        'id -> projectLibrary.id,
        'updated_by_user_id -> user.id
      ).execute()
    }
  }

  /**
    * Removes any project library ids for this project not specified in this list
    */
  def setIds(user: UserReference, projectId: String, projectBinaries: Seq[ProjectLibrary]) {
    val ids = projectBinaries.map(_.id)
    Pager.create { offset =>
      findAll(Authorization.All, projectId = Some(projectId), limit = 100, offset = offset)
    }.foreach { projectLibrary =>
      if (!ids.contains(projectLibrary.id)) {
        delete(user, projectLibrary)
      }
    }

  }

  def setLibrary(user: UserReference, projectLibrary: ProjectLibrary, library: Library) {
    DB.withConnection { implicit c =>
      SQL(SetLibraryQuery).on(
        'id -> projectLibrary.id,
        'library_id -> library.id,
        'updated_by_user_id -> user.id
      ).execute()
    }
  }

  def delete(deletedBy: UserReference, library: ProjectLibrary) {
    DbHelpers.delete("project_libraries", deletedBy.id, library.id)
    MainActor.ref ! MainActor.Messages.ProjectLibraryDeleted(library.project.id, library.id, library.version)
  }

  def findByProjectIdAndGroupIdAndArtifactIdAndVersion(
    auth: Authorization,
    projectId: String,
    groupId: String,
    artifactId: String,
    version: VersionForm
  ): Option[ProjectLibrary] = {
    findAll(
      auth,
      projectId = Some(projectId),
      groupId = Some(groupId),
      artifactId = Some(artifactId),
      version = Some(version.version),
      crossBuildVersion = Some(version.crossBuildVersion),
      limit = 1
    ).headOption
  }

  def findById(auth: Authorization, id: String): Option[ProjectLibrary] = {
    findAll(auth, id = Some(id), limit = 1).headOption
  }

  def findAll(
    auth: Authorization,
    id: Option[String] = None,
    ids: Option[Seq[String]] = None,
    projectId: Option[String] = None,
    libraryId: Option[String] = None,
    groupId: Option[String] = None,
    artifactId: Option[String] = None,
    version: Option[String] = None,
    crossBuildVersion: Option[Option[String]] = None,
    isSynced: Option[Boolean] = None,
    hasLibrary: Option[Boolean] = None,
    orderBy: OrderBy = OrderBy("lower(project_libraries.group_id), lower(project_libraries.artifact_id), project_libraries.created_at"),
    limit: Long = 25,
    offset: Long = 0
  ): Seq[ProjectLibrary] = {

    DB.withConnection { implicit c =>
      Standards.query(
        BaseQuery,
        tableName = "project_libraries",
        auth = auth.organizations("organizations.id", Some("projects.visibility")),
        id = id,
        ids = ids,
        orderBy = orderBy.sql,
        limit = limit,
        offset = offset
      ).
        equals("project_libraries.project_id", projectId).
        equals("project_libraries.library_id", libraryId).
        optionalText(
          "project_libraries.group_id",
          groupId,
          columnFunctions = Seq(Query.Function.Lower),
          valueFunctions = Seq(Query.Function.Lower, Query.Function.Trim)
        ).
        optionalText(
          "project_libraries.artifact_id",
          artifactId,
          columnFunctions = Seq(Query.Function.Lower),
          valueFunctions = Seq(Query.Function.Lower, Query.Function.Trim)
        ).
        optionalText(
          "project_libraries.version",
          version
        ).
        and(
          crossBuildVersion.map { v =>
            v match {
              case None => "project_libraries.cross_build_version is null"
              case Some(_) => "project_libraries.cross_build_version = {cross_build_version}"
            }
          }
        ).
        bind("cross_build_version", crossBuildVersion.flatten).
        and(
          isSynced.map { value =>
            val clause = "select 1 from syncs where object_id = project_libraries.id and event = {sync_event_completed}"
            value match {
              case true => s"exists ($clause)"
              case false => s"not exists ($clause)"
            }
          }
        ).
        bind("sync_event_completed", isSynced.map(_ => SyncEvent.Completed.toString)).
        nullBoolean("project_libraries.library_id", hasLibrary).
        as(
          com.bryzek.dependency.v0.anorm.parsers.ProjectLibrary.parser().*
        )
    }
  }

}