package com.medcore.architecture

import com.medcore.platform.audit.AuditAction
import com.medcore.platform.audit.AuditEventCommand
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import org.springframework.transaction.annotation.Transactional

/**
 * Machine-enforced security + audit-emission disciplines
 * (Phase 3I.1, ADR-003 / ADR-007 / Rule 01-06).
 *
 * These rules enforce the "audit is domain-driven, not
 * request-driven" principle from ADR-003 §2 + the transaction-
 * ownership rule from ADR-007 §2.2.
 */
@AnalyzeClasses(
    packages = ["com.medcore"],
    importOptions = [com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests::class],
)
class SecurityDisciplineArchTest {

    /**
     * RULE 9 — `AuditEventCommand` is constructed only inside
     * sanctioned layers.
     *
     * Controllers (`.api`) MUST NOT construct audit commands —
     * audit emission is the domain layer's responsibility, not
     * the transport layer's. Keeps the audit contract
     * domain-driven rather than request-driven; controllers stay
     * thin and swappable (CLI, webhook, test harness can invoke
     * the domain without re-implementing audit).
     *
     * Sanctioned packages:
     *   - `.write`      — per-command auditors (success + denial)
     *   - `.service`    — read-side emitters (membership list,
     *                     tenant context set/denied)
     *   - `.audit`      — `JdbcAuditWriter`, chain verifier
     *   - `.persistence` — migration-state helpers, if any
     *
     * Narrow cross-cutting exceptions (each is a pre-controller
     * infrastructure hook with no handler layer to route through):
     *
     *   - `..identity..` — `IdentityProvisioningService` emits
     *     at JIT-provision time, before any controller runs.
     *     The JIT path pre-dates WriteGate; a future slice may
     *     route it through a WriteGate-gated identity handler.
     *   - `com.medcore.platform.security..` —
     *     `AuditingAuthenticationEntryPoint` emits
     *     IDENTITY_USER_LOGIN_FAILURE on 401, BEFORE Spring MVC
     *     dispatches to a controller. No domain layer exists to
     *     emit from here.
     *   - `com.medcore.tenancy.context..` — `TenantContextFilter`
     *     emits TENANCY_CONTEXT_SET / _DENIED at filter time,
     *     again pre-controller. Filter-chain audit is unavoidable
     *     for tenant-context establishment.
     */
    @ArchTest
    val audit_event_command_constructed_only_in_sanctioned_layers: ArchRule =
        noClasses()
            .that().resideOutsideOfPackages(
                "..write..",
                "..read..",   // Phase 4A.4: ReadAuditor implementations
                              // construct AuditEventCommand for
                              // CLINICAL_PATIENT_ACCESSED / AUTHZ_READ_DENIED.
                "..service..",
                "..audit..",
                "..persistence..",
                // Cross-cutting pre-controller hooks (see KDoc):
                "..identity..",
                "com.medcore.platform.security..",
                "com.medcore.tenancy.context..",
            )
            .should().dependOnClassesThat().areAssignableTo(AuditEventCommand::class.java)
            .`as`(
                "AuditEventCommand is constructed only in domain " +
                    "layers (.write handlers, .service read-side " +
                    "emitters) or in the explicitly allow-listed " +
                    "cross-cutting pre-controller hooks (identity " +
                    "JIT provisioning, security entry point, tenant " +
                    "context filter). Controllers must not construct " +
                    "audit commands — audit is domain-driven " +
                    "(ADR-003 §2).",
            )

    /**
     * RULE 10 — `@Transactional` is NOT applied to controllers.
     *
     * Transaction ownership lives in WriteGate for mutations
     * (ADR-007 §2.2) and in service methods for reads.
     * Controllers are thin; any `@Transactional` there is a
     * smell — it either duplicates the gate's boundary (no-op) or
     * opens a second transaction policy (confusion) or tries to
     * coordinate multiple services in one tx (architectural
     * boundary violation — compose at the service layer instead).
     */
    @ArchTest
    val no_transactional_on_controller_classes: ArchRule =
        noClasses()
            .that().resideInAPackage("..api..")
            .should().beAnnotatedWith(Transactional::class.java)
            .`as`(
                "@Transactional MUST NOT appear at the class level on " +
                    "controllers (.api). Transaction ownership lives in " +
                    "WriteGate for mutations and in service methods for " +
                    "reads. Controllers are thin; compose at the " +
                    "service layer.",
            )

    @ArchTest
    val no_transactional_on_controller_methods: ArchRule =
        noMethods()
            .that().areDeclaredInClassesThat().resideInAPackage("..api..")
            .should().beAnnotatedWith(Transactional::class.java)
            .`as`(
                "@Transactional MUST NOT appear on controller methods " +
                    "(.api). Same rationale as the class-level rule.",
            )

    /**
     * RULE 11 — `AuditAction` enum references live only in
     * sanctioned layers.
     *
     * Coarse-grain enforcement that audit-action decisions are
     * made in the domain, not the transport. Allowed packages:
     * `.write` (per-command auditors), `.service` (read-side
     * emission), `.audit` (chain verifier + JdbcAuditWriter), and
     * the enum's own declaring package (`platform.audit`).
     */
    @ArchTest
    val audit_action_references_in_sanctioned_layers_only: ArchRule =
        noClasses()
            .that().resideOutsideOfPackages(
                "..write..",
                "..read..",   // Phase 4A.4: ReadAuditor packages emit
                              // read-specific audit actions.
                "..service..",
                "..audit..",
                "..persistence..",
                "com.medcore.platform.audit..",
                // Cross-cutting pre-controller hooks (see Rule 9 KDoc):
                "..identity..",
                "com.medcore.platform.security..",
                "com.medcore.tenancy.context..",
            )
            .should().dependOnClassesThat().areAssignableTo(AuditAction::class.java)
            .`as`(
                "AuditAction references live only in .write auditors, " +
                    ".service read-side emitters, .audit infrastructure, " +
                    "or the explicitly allow-listed cross-cutting hooks. " +
                    "Controllers must not pick audit actions — that " +
                    "decision belongs to the domain.",
            )
}
