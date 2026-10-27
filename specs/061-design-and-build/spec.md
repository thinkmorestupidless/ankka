# Feature Specification: Design and build

**Feature Branch**: `061-design-and-build`

**Created**: 2026-10-27

**Status**: Draft

**Input**: User description: "Two spec-kit commands that replace the eight-step feature workflow,
and a documentation page that presents them as the way to build with ankka."

## Context

A feature in an ankka family repository is specified, planned, built and reviewed with spec-kit:
`/speckit-specify`, `/speckit-clarify`, `/speckit-plan`, `/speckit-tasks`, `/speckit-analyze`,
`/speckit-implement`, the review that follows implement, and the pull request. Two extensions sit
beside the core commands: `bdd`, whose hooks write each spec's scenarios into the living features
and run the checker before clarify, and `review`, whose hook after implement reads the branch with a
fresh context. The family's conventions say which model each step runs on: the deeper-thinking
model for everything that decides, from the description to the analysed tasks, and a cheaper model
once everything is decided, for the code. They also say where the person is asked and where they
are not.

Today the person drives all of it: eight commands typed in order, every remediation from analyze
applied by hand and analyze run again, and a new session started with the other model for the
building half. The order and the models are written down, but nothing holds anyone to them, and a
step skipped or a model not switched is found later or not at all. Two of the eight steps need the
person's answers; the rest need only the previous step's output.

This feature folds the eight steps into two commands, each on its own model. `/speckit-design`
takes a feature description and ends with a spec, its living features, a plan and an analysed task
list, asking the person what it needs to know along the way. `/speckit-build` takes that task list
and ends with a reviewed branch and a pull request ready for review with its checks green, asking
the person nothing unless the design left something open. They are a third spec-kit extension, kept in ankka and copied to each
family repository as `review` is, and the existing commands stay as they are for repairing one
stage. A documentation page presents the pair as the way to build with ankka: describe the feature,
answer the questions, approve the pull request.

## Clarifications

### Session 2026-10-27

- Q: Where does the build command end? → A: It raises the draft pull request, watches its checks,
  attends to a red one (a fix on the branch, or evidence that it is a known flake of the default
  branch, said in the pull request), marks the pull request ready when every check is green, and
  tells the person a review is waiting.
- Q: Where are the two model choices and the reason recorded? → A: In the command definitions only:
  each command's model line is the single copy, and the extension's description says why the halves
  differ and where the lines are. No configuration file.
- Q: Where does the documentation page sit? → A: A new page that opens the get-started section and
  is the first link on the opening page's "Where to start"; the opening page keeps its shape and
  gains a paragraph pointing there.
- Q: Do the glossary's proposed terms stand? → A: Yes, all as written: design and build, design,
  build, stage, spec, plan, living feature, scenario, glossary, checker, change's directory, work
  list, work item, clarification, remediation, finding, review, model line, design model, build
  model, branch, default branch, pull request, base, hook. The spec itself keeps saying "extension"
  for the packaging, spec-kit's own word.
- Q: Is installing design and build in the sibling repositories part of this feature? → A: Yes,
  everywhere: every family repository whose checkout has spec-kit gets design and build, and
  whatever of `bdd` and `review` it lacks, in this feature. Today only ankka has both; voice,
  ankka-flow, ankka-reasoning, ankka-predictions, ankka-projects, ankka-for-trades,
  ankka-for-plumbers, ankka-for-plumbers-android, ankka-for-plumbers-ios, programme and
  ankka-modules have `bdd` alone and get `review` and design and build; satisfactory, ankka-vendor
  and ankka-replication have no extension and get all three. A repository without spec-kit is
  outside this feature.
- Q: How does the page say a project outside the template gets design and build? → A: The steps
  by hand: install spec-kit, initialise it with the Claude integration, copy the `bdd`, `review`
  and `design-build` extension directories from the ankka repository, register them, install the
  `pr-review` plugin. No install script.
- Q: How long does the build wait for the pull request's checks? → A: Until they finish, polling,
  with no fixed bound; a run cut short is resumed by typing the build again, which finds the pull
  request and resumes at its checks.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A feature designed with one command (Priority: P1)

A person in a family repository types `/speckit-design` followed by a feature description. The
command runs specify, which writes the spec and, through the `bdd` hook, the living features and
the glossary. Each clarification marker the spec raises is put to the person as options with their
implications, and the answer goes into the spec. The command runs clarify, whose hook runs the
checker first; each of clarify's questions is put to the person one at a time and the answer is
encoded into the spec. The command confirms the checker is green, runs plan and tasks, then runs
analyze; it applies every remediation analyze proposes and runs analyze again until nothing above
low severity remains. A remediation that would change a decision the person made, or a scenario in
the living features, is put to the person instead of applied. The command ends by saying the design
is ready and printing the exact command that starts the build. Throughout, the command runs on the
model the repository chose for design, and every question keeps the command running on it.

**Why this priority**: The design half is where the person's time goes and where a skipped step
costs the most later. Until it runs as one command on one model, the second command has nothing
reliable to start from.

**Independent Test**: In a repository with the extension installed, type the command with a short
feature description. Answer its questions. Check that the feature directory holds the spec, the plan
and the task list, that the living features hold the scenarios the spec names, that the checker is
green, that the last analyze report has nothing above low, and that every question was asked in the
form the command prescribes.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/design-and-build/design.feature`: a design started with a description ends with a spec, a plan and a work list
- added `features/design-and-build/design.feature`: a design started without a description asks for one and does nothing else
- added `features/design-and-build/design.feature`: a clarification the spec raises is settled by the developer before the clarify stage runs
- added `features/design-and-build/design.feature`: the clarify stage's clarifications are put to the developer one at a time
- added `features/design-and-build/design.feature`: the checker reports nothing before the plan stage runs
- added `features/design-and-build/design.feature`: a remediation that changes no decision and no scenario is applied without asking
- added `features/design-and-build/design.feature`: a remediation that would change a decision or a scenario is put to the developer first
- added `features/design-and-build/design.feature`: a remediation that changes a scenario goes through the living features and the checker
- added `features/design-and-build/design.feature`: a remediation the developer declined is not proposed again
- added `features/design-and-build/design.feature`: the design stops analysing when no finding above low remains
- added `features/design-and-build/design.feature`: the design stops analysing after three rounds and shows what remains
- added `features/design-and-build/design.feature`: a design resumed from a stage leaves the earlier stages' work as it was
- added `features/design-and-build/design.feature`: a design resumed from a stage whose inputs do not exist says which stage must run first
- added `features/design-and-build/design.feature`: the design refuses to write a spec on the default branch
- added `features/design-and-build/design.feature`: the design stops before any stage when bdd is not installed
- added `features/design-and-build/design.feature`: the design stops before any stage when the developer cannot be asked anything
- added `features/design-and-build/design.feature`: a clarification is put to the developer without the design ending

---

### User Story 2 - A feature built with one command (Priority: P1)

A person whose feature was designed types `/speckit-build`, in the same checkout and nothing of the
design session, and the command runs on the model the repository chose for building. It reads the
feature pointer, the plan and the task list and nothing else from the design. It implements every
task in order, committing each and going on to the next without stopping to report. When the tasks
are done it runs the review the `review` extension provides: a fresh-context reviewer reads the
branch, every finding is fixed or declined with a reason, and the outcome is recorded. It then
raises the pull request as a draft on the right base, with the body the repository's template asks
for and the dependency on an unmerged base said in the first line. It watches the pull request's
checks; a red one it fixes on the branch, or shows with evidence to be a known flake of the default
branch and says so in the pull request. When every check is green it marks the pull request ready
and tells the person a review is waiting. It stops for the person only on something the spec or
plan left open, something that would change a contract, a check it cannot make green, or a blocking
review finding it disagrees with.

**Why this priority**: The build half is the half that spends the most tokens, so running it on the
cheaper model by construction, rather than by remembering to, is the point of having two commands.

**Independent Test**: In a repository whose feature directory holds an analysed task list, type the
build command. Check that each task's tick and commit landed in order, that the review report exists
with a resolution under every finding, that a pull request exists on the right base with the
template's sections filled and is marked ready only after its checks passed, and that the command
asked nothing the design had settled.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/design-and-build/build.feature`: a build started on a designed change ends with a reviewed branch and a pull request
- added `features/design-and-build/build.feature`: a build reads only what the design wrote
- added `features/design-and-build/build.feature`: a finished work item is committed and the next starts without a report
- added `features/design-and-build/build.feature`: a decision the spec and the plan left open is put to the developer and the build goes on from that item
- added `features/design-and-build/build.feature`: a work item whose check cannot be made to pass stops the build at that item
- added `features/design-and-build/build.feature`: the review runs after the last work item and the pull request waits for it
- added `features/design-and-build/build.feature`: a blocking finding the build disagrees with stops the build before the pull request
- added `features/design-and-build/build.feature`: a change cut from an unmerged branch targets that branch and says which pull request it waits on
- added `features/design-and-build/build.feature`: a pull request is raised not yet marked ready
- added `features/design-and-build/build.feature`: a pull request whose checks are all green is marked ready and the developer told
- added `features/design-and-build/build.feature`: a red check the branch causes is fixed before the pull request is marked ready
- added `features/design-and-build/build.feature`: a red check the default branch also fails is shown as such and does not hold the pull request
- added `features/design-and-build/build.feature`: a check the build cannot make green leaves the pull request not ready and the developer told
- added `features/design-and-build/build.feature`: a build started on a partly done work list resumes at the first undone item
- added `features/design-and-build/build.feature`: a build started on a change whose work is done and reviewed resumes at the pull request
- added `features/design-and-build/build.feature`: a build started without a work list says the design is not finished
- added `features/design-and-build/build.feature`: a build started on a work list whose analysis did not finish says the design is not finished
- added `features/design-and-build/build.feature`: a build started again after the review stopped on a finding returns to the review
- added `features/design-and-build/build.feature`: the build stops before any stage when review is not installed
- added `features/design-and-build/build.feature`: the build neither merges, tags, publishes nor deploys

---

### User Story 3 - Each half on its own model, chosen per repository (Priority: P2)

A repository's maintainer decides which model designs and which builds. The default is the
deeper-thinking model for design and a cheaper one for build. The choice is one line per command,
in the command's own definition, and is applied by the tool for the whole run of that command. The
maintainer of another repository can choose differently by editing the lines after copying the
extension. The lines are the only record of the choice; the extension's description says why the
two halves differ and where the lines are.

**Why this priority**: The model split is the reason for the two commands; it must be a fact of the
repository, not a habit of the person.

**Independent Test**: Read the two command definitions in a repository and find the model line in
each. Change one, run the command, and confirm the run used the changed model. Copy the extension
into a second repository and confirm its lines can differ from the first's.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/design-and-build/models.feature`: the whole design runs on the design model, clarifications included
- added `features/design-and-build/models.feature`: the whole build runs on the build model
- added `features/design-and-build/models.feature`: as shipped, the design model thinks deeper than the build model
- added `features/design-and-build/models.feature`: a model line edited in one definition changes that one's model and not the other's
- added `features/design-and-build/models.feature`: the model lines are the only record of the models, and the reason is beside them
- added `features/design-and-build/models.feature`: a coding agent started on another model runs the design on the design model and returns after

---

### User Story 4 - The extension installed as `review` is (Priority: P2)

The two commands are a spec-kit extension kept in ankka, beside `bdd` and `review`. A family
repository gets them by copying the extension's directory and its two skill directories and
registering it, exactly as it gets `review`. The extension's hooks and the commands it invokes are the existing ones: specify's,
clarify's and implement's hooks still fire inside the stages the two commands run, and no existing
command changes. The family's conventions document replaces its eight-row table with the two
commands and keeps the individual commands for repairing one stage.

**Why this priority**: The family is several repositories; a workflow that lives in one is not the
family's.

**Independent Test**: Copy the extension into a second family repository the way `review` was copied,
register it, and run the design command there. Check that the `bdd` hooks fired inside its specify
and clarify stages and that the second repository's existing commands are unchanged.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/design-and-build/installing.feature`: design and build copied into a repository and registered can be started there
- added `features/design-and-build/installing.feature`: the bdd hooks fire inside the design's specify and clarify stages
- added `features/design-and-build/installing.feature`: the review hook fires inside the build after the last work item
- added `features/design-and-build/installing.feature`: design and build changes nothing it runs
- added `features/design-and-build/installing.feature`: a stage can still be run alone to repair it
- added `features/design-and-build/installing.feature`: the description of design and build says what it needs and what it is for

The family's conventions document is outside this repository; FR-027 covers it and no scenario here
does.

---

### User Story 5 - The way to build with ankka, in the documentation (Priority: P2)

A developer new to ankka opens the documentation and learns that a feature is built by describing
it, answering the design command's questions, and approving the pull request the build command
raises. The page tells them what each command produces, where they will be asked and where they will
not, that two models are used and why they differ, how the extension is installed in a project made
from the ankka template or any other project, and what the spec directory, the living features and
the glossary are. It opens the get-started section and is the first link on the opening page's
"Where to start", so it comes before the page on skills and the MCP server, as the way in; that
page stays as the detail of what the agent is given, and the opening page keeps its shape and gains
a paragraph pointing to the new one.

**Why this priority**: The commands exist for people who build on ankka, not for the family alone.
Without the page, the way to build is known to those who read the family's conventions.

**Independent Test**: Read the page as a developer who has installed the tools and nothing else.
Follow it to install the extension in a project made from the template, type the design command,
and recognise each thing the page said would happen. Run the documentation check and confirm the
page passes.

**Acceptance Scenarios** *(each names a scenario in a living feature; none is written here)*:

- added `features/documentation/design-and-build.feature`: the opening page points first to the page that presents the design and then the build
- added `features/documentation/design-and-build.feature`: the page says what the design and the build produce and where the developer is asked
- added `features/documentation/design-and-build.feature`: the page says how design and build is installed and what the spec, the living features and the glossary are
- added `features/documentation/design-and-build.feature`: the page passes the documentation check and names the design and the build as a developer starts them

---

### Edge Cases

- The person types the design command on the repository's default branch. The command refuses to
  write a spec there and says which branch to make.
- The person types the design command in a repository without the `bdd` extension. The command says
  the extension is required and stops before specify.
- The person types the design command in a session that cannot ask questions, such as a headless
  run. The command stops before specify and says it needs an interactive session.
- The person types the build command in a repository without the `review` extension. The command
  says the extension is required and stops before implement, so it never raises a pull request
  that carries no review.
- The design command's feature pointer names a directory that does not exist, or the build
  command's names one with no task list. Each stops and says what is missing and which command
  makes it.
- Analyze keeps finding something above low after its remediations are applied. The command stops
  after a bounded number of rounds, shows what remains, and asks the person rather than looping.
- A remediation proposed by analyze is one the person already declined in an earlier round. The
  command does not apply it or ask again; it records that it was declined.
- The person answers a clarification with their own words rather than one of the options. The answer
  is encoded as given.
- The build command's implement stage fails a task's test and cannot make it pass. It stops at that
  task with the failure, as implement does today, and the pull request is not raised.
- The review finds a blocking issue the implementer disagrees with. The command stops and shows the
  finding and its reason; it does not raise the pull request.
- The pull request's base branch has a pull request the person has not approved. The command still
  targets it, and says so in the pull request's first line.
- A check is red for a reason not on the branch: a flake the default branch also shows. The
  command shows the evidence (the same failure on a run of the default branch) in the pull request
  and does not count it against marking ready; a red check without such evidence is the branch's.
- The build's run is cut short while it is polling the checks. The pull request is still a draft;
  typing the build command again finds the tasks ticked, the review done and the pull request
  raised, and resumes at the checks.
- The reviewer's skill (`pr-review`) is not installed when the build starts. The build stops before
  implement and says how to install it, rather than implementing for hours and failing at the
  review.
- A sibling repository has spec-kit but not `bdd` or `review`. It is given what it lacks before
  design and build: `review` as a copy of ankka's; `bdd` as the extension, an empty glossary, an
  empty features directory and a configuration whose first checked spec is the next one written,
  so the specs it already has are not read and stay as they are.
- A sibling repository has no spec-kit. It is outside this feature and named as such in the pull
  request.
- The person types the design command with the resume option naming a stage whose inputs do not
  exist yet. The command says which earlier stage must run first.
- The tool is started on a model other than the one a command names. The command's model line
  overrides it for the run, and the session returns to its own model after the run.
- The documentation page names the commands by their names, and those names change. The page is
  checked against the extension's command names, so a rename fails the check.

## Requirements *(mandatory)*

### Functional Requirements

**The extension**

- **FR-001**: The two commands MUST be one spec-kit extension kept in the ankka repository beside
  `bdd` and `review`, with the extension's manifest, its command files and a description of what
  it provides, installed in a repository the way `review` is installed.
- **FR-002**: The extension MUST require the `bdd` and `review` extensions and MUST say so where a
  person installs it; a command that finds one missing MUST stop before any stage runs and say
  which is missing. The build command MUST also check, before implement, that the reviewer's skill
  the `review` extension needs is installed, and stop with the install instructions when it is not.
- **FR-003**: The extension MUST NOT change any existing spec-kit command, the `bdd` extension, the
  `review` extension or the checker. The two commands MUST invoke the existing commands for each
  stage.
- **FR-004**: The hooks registered on the existing commands MUST fire inside the stages the two
  commands run, exactly as they fire when a stage is run on its own.

**The design command**

- **FR-005**: The design command MUST take the feature description typed after it. When none was
  typed it MUST ask for one and do nothing else until one is given.
- **FR-006**: The design command MUST run, in order: specify, the resolution of every clarification
  the spec raises, clarify, the checker gate, plan, tasks, and analyze with its remediation loop.
- **FR-007**: Every clarification the spec raises MUST be put to the person as options with their
  implications, and the answer MUST be written into the spec before clarify runs.
- **FR-008**: Clarify's questions MUST be put to the person one at a time, and each answer MUST be
  encoded into the spec before the next question.
- **FR-009**: The checker MUST be green before plan runs. A finding MUST be resolved as clarify
  resolves one: by a question to the person or a change to the spec, the features or the glossary.
- **FR-010**: After analyze, the design command MUST apply every remediation analyze proposes that
  changes neither a decision the person made nor a scenario in the living features, and run analyze
  again, until no finding above low severity remains or three rounds have run.
- **FR-011**: A remediation that would change a decision the person made or a scenario MUST be put
  to the person before it is applied, and one the person declined MUST NOT be proposed again.
- **FR-012**: When a remediation changes a scenario, the change MUST go through the living features
  and the checker MUST be green again before analyze runs again.
- **FR-013**: The design command MUST end by saying the design is ready, where its artifacts are,
  and the exact command that starts the build.
- **FR-014**: The design command MUST accept a resume option naming a stage, run from that stage
  on, and leave the artifacts of earlier stages as they were. When the named stage's inputs do not
  exist it MUST say which earlier stage must run first.
- **FR-015**: The design command MUST refuse to write a spec on the repository's default branch.
- **FR-016**: Every question the design command asks MUST be asked in the form that keeps the
  command running and its model in force; it MUST NOT ask by ending with a message that waits for
  the person's next prompt. In a session where that form is not available, such as a headless
  run, it MUST stop before any stage and say it needs one where it is, rather than decide in the
  person's place.

**The build command**

- **FR-017**: The build command MUST read the feature pointer, the plan and the task list and MUST
  NOT depend on anything from the session that designed the feature.
- **FR-018**: The build command MUST run, in order: implement, the review the `review` extension
  provides, and the pull request.
- **FR-019**: Implement MUST commit each task and go on to the next without stopping to report,
  printing one line of progress per task; a partly ticked task list MUST resume from its first
  unticked task.
- **FR-020**: The build command MUST stop for the person only on a decision the spec and plan left
  open, a change that would alter a contract, a task whose check it cannot make pass, or a blocking
  review finding it disagrees with. Each MUST be put as options with their implications where
  options exist.
- **FR-021**: The pull request MUST be raised as a draft, on the default branch unless the feature
  branch was cut from an unmerged feature branch, in which case on that branch with the dependency
  said in the first line; its title MUST follow the repository's commit convention and its body the
  repository's pull request template, carrying the review's outcome lines.
- **FR-022**: After raising the pull request, the build command MUST watch its checks by polling
  until every one has finished, with no fixed bound on the wait; a red check
  MUST be fixed on the branch or shown, with evidence, to be a known flake of the default branch
  and said in the pull request; when every check has finished and each is green or so shown, the
  command MUST mark the pull request ready and tell the person a review is waiting. A red check it
  can neither fix nor show to be such a flake MUST stop the command with the pull request still a
  draft.
- **FR-022a**: The build command MUST NOT merge, tag, publish or deploy, and MUST NOT mark the pull
  request ready while any check is pending, or red without that evidence.
- **FR-023**: When no task list exists, or the design did not record that its analysis ran to its
  end, the build command MUST stop and name the command that finishes the design.
- **FR-023a**: A build started again MUST raise the pull request only once every review finding has
  a resolution recorded; a review that stopped on a finding goes back to the review.

**The models**

- **FR-024**: Each command MUST name the model it runs on in its own definition, in the form the
  tool applies for the whole run of that command, so that a run on the other model requires editing
  that line.
- **FR-025**: The defaults MUST be the deeper-thinking model for design and a cheaper one for build.
- **FR-026**: The model lines MUST be the only record of the choice: the extension MUST ship no
  configuration file naming a model, and its description MUST say why the halves differ and that
  each command's definition holds its line.

**The conventions**

- **FR-027**: The family's conventions document MUST describe the feature workflow as the two
  commands: the stages each runs, where each asks the person, and the individual commands kept for
  repairing one stage.
- **FR-027a**: The extension MUST be copied and registered in every family repository whose
  checkout has spec-kit, each on a branch of that repository with its own commits and pull request,
  together with whatever of `bdd` and `review` the repository lacks: `review` as a copy of ankka's
  current extension and skill; `bdd` as the extension, an empty glossary, an empty features
  directory, the checker wired into that repository's checks, and its first checked spec set to the
  next spec written there, so existing specs are not read. Nothing already in a sibling repository
  MUST change but its extension registration, its skills directory and, for `bdd`, its checks.
  Repositories without spec-kit MUST be named as outside this feature in its pull request.

**The documentation page**

- **FR-028**: ankka's public documentation MUST carry a page that presents building with ankka as
  design then build: describe the feature, answer the questions, approve the pull request.
- **FR-029**: The page MUST say what each command produces, where the person is asked and where
  they are not, that the two commands run on different models and why, how the extension is
  installed in a project made from the template and in any other project, and what the spec
  directory, the living features and the glossary are. For a project outside the template the page
  MUST give the steps by hand: install spec-kit, initialise it with the Claude integration, copy the
  `bdd`, `review` and `design-build` extension directories from the ankka repository, register
  them, install the `pr-review` plugin.
- **FR-030**: The page MUST open the get-started section and be the first link on the opening
  page's "Where to start"; the opening page MUST keep its shape and gain a paragraph pointing to
  it; the page on skills and the MCP server MUST stay as the detail of what the agent is given.
- **FR-031**: The page MUST pass the documentation check: complete frontmatter, standing alone,
  telling no history, every link resolving; and its command names MUST be checked against the
  extension's.

### Key Entities

- **The extension**: a spec-kit extension, `design-build`, with a manifest, two command files and
  a description; kept in ankka, copied to each family repository.
- **The design command**: `/speckit-design`; takes a description or a resume stage; runs the
  stages from specify to analyze on the design model; produces the spec, the living features and
  glossary entries, the plan and its design artifacts, the task list, and the analyze report.
- **The build command**: `/speckit-build`; takes nothing; runs implement, the review and the pull
  request on the build model; produces the commits, the ticks, the review report and the pull
  request, a draft until its checks are green and ready after.
- **A stage**: one of specify, clarifications, clarify, checker, plan, tasks, analyze, implement,
  review, pull request; the unit the design command resumes from and the individual commands repair.
- **The feature directory**: `specs/NNN-name/`, named by the feature pointer, holding the
  artifacts the stages produce.
- **The model line**: the one line in a command's definition naming the model the tool runs that
  command on.
- **The page**: the documentation page that presents design then build; the first page of the
  get-started section.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A feature goes from a description to a pull request ready for review with the person
  typing two commands and switching models by hand zero times.
- **SC-002**: Every question put to the person during design is put through one mechanism, and the
  design run stays on its model from its first question to its last; a run that ends on a model
  other than the one its command names is a defect.
- **SC-003**: After design, the checker is green, the analyze report has nothing above low, and
  every spec clarification and clarify question has its answer in the spec, with no step of the
  eight done by hand.
- **SC-004**: After build, every task is ticked with a commit, the review report has a resolution
  under every finding, and the pull request is on the right base with its body complete and marked
  ready only after every check passed, with nothing pushed before the review.
- **SC-005**: The extension is installed in a second family repository by copying its directory
  and its two skill directories and adding its registration, and its first design run there fires
  the `bdd` hooks.
- **SC-006**: The models are changed in one repository by editing one line per command, and the
  other repositories are unaffected.
- **SC-007**: A developer who has read only the page knows the two commands, what each produces,
  where they will be asked, and how to install the extension; the page passes the documentation
  check.
- **SC-008**: The family's conventions describe the workflow in two rows rather than eight, and
  nothing in the two commands contradicts a rule that stays.
- **SC-009**: Every family repository with spec-kit (fourteen besides ankka today) has `bdd`,
  `review` and design and build registered, the design command starts in each, and the checker
  reports a clean project in each; the repositories without spec-kit are listed as outside.

## Assumptions

- The tool applies a command's model line for the run of that command, and a question asked
  through the tool's question mechanism keeps the run open; a message that ends the turn does not.
  The design command depends on this, so its questions go only through that mechanism.
- The existing commands are invoked as commands from inside the two new ones, and their hooks fire
  as they do when the person types them. The family's commands were written to be chained this way.
- A command runs in the main session, not in a delegated context, because only the main session
  can ask the person a question. Orchestration that delegates stages is out of scope.
- The build command reviews the branch and raises the pull request itself; the review's reviewer is
  the fresh-context one the `review` extension already spawns, unchanged.
- The person makes the feature branch and the checkout before typing the design command, as they do
  today; the design command checks it is not on the default branch rather than making branches.
- The extension's name is `design-build`; the commands are `speckit.design` and `speckit.build`,
  rendered to `/speckit-design` and `/speckit-build`.
- The bounded number of analyze rounds is three; what remains after the third is put to the person.
- The documentation page is written for the audience of the get-started section, in the voice of
  the existing pages, and is rendered into the orientation skill as the pages of that section are.
- The build command resumes as implement does: a feature whose tasks are ticked and whose review is
  recorded resumes at the pull request, and one whose pull request exists resumes at its checks.
- The conventions document is in the family brain, a sibling checkout, and is changed in the same
  piece of work; the private brain carries nothing about the workflow that needs changing.
- The sibling repositories are the checkouts beside ankka (and ankka-modules in its own place);
  each sibling's change is a branch and a pull request of its own, raised as a draft and left for
  the person, since this feature's own build command does not exist until this pull request merges.
  ankka-modules belongs to another company's brain; its copy is made the same way and said there.
- The page names the commands; a check in the documentation tool or the features check holds the
  names to the extension's.

## Out of Scope

- Changing spec-kit, the `bdd` extension, the `review` extension or the checker.
- Running any stage in a delegated context or a subagent; the two commands run where they are
  typed.
- Making the feature branch, the checkout or the worktree.
- The service template and `ankka init` carrying the extension into new projects; a follow-up.
- Publishing the extension anywhere other than the ankka repository, from which it is copied.
- Moving a sibling repository's existing specs' scenarios into living features when it gains
  `bdd`; its first checked spec is the next one, and the earlier ones stay as written.
- Repositories without spec-kit (ankka-contrib, ankka-deployments, cloudflow, ankka-forge,
  forge-application, voice-website).
- Any install script; the documentation gives the steps by hand.
- Any change to the review's reviewer, its brief or its report.
