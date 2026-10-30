-- mmorpg: 1.0.0-beta fresh-install schema. Not an upgrade path.
-- Apply through the migration runner; never edit after this baseline is released.
SET search_path = public;
--
--


SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: mmo_battle; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mmo_battle (
    id bigint NOT NULL,
    scene_ref character varying(64) NOT NULL,
    scene_session_id bigint NOT NULL,
    role_id bigint NOT NULL,
    channel_id bigint DEFAULT 0 NOT NULL,
    mode character varying(32) DEFAULT 'pve_basic'::character varying NOT NULL,
    phase character varying(16) DEFAULT 'CREATED'::character varying NOT NULL,
    round_no integer DEFAULT 0 NOT NULL,
    phase_version bigint DEFAULT 0 NOT NULL,
    state_version bigint DEFAULT 0 NOT NULL,
    public_event_seq bigint DEFAULT 0 NOT NULL,
    private_event_seq bigint DEFAULT 0 NOT NULL,
    rng_seed bigint NOT NULL,
    rng_cursor bigint DEFAULT 0 NOT NULL,
    winner_side smallint DEFAULT '-1'::integer NOT NULL,
    deadline_at_ms bigint DEFAULT 0 NOT NULL,
    initiative_order text DEFAULT '[]'::text NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: mmo_battle_actor; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mmo_battle_actor (
    id bigint NOT NULL,
    battle_id bigint NOT NULL,
    side smallint NOT NULL,
    owner_role_id bigint DEFAULT 0 NOT NULL,
    name character varying(64) NOT NULL,
    kind character varying(16) NOT NULL,
    "position" integer DEFAULT 0 NOT NULL,
    hp bigint NOT NULL,
    max_hp bigint NOT NULL,
    mp bigint DEFAULT 0 NOT NULL,
    max_mp bigint DEFAULT 0 NOT NULL,
    atk bigint NOT NULL,
    defense bigint NOT NULL,
    speed integer NOT NULL,
    alive smallint DEFAULT 1 NOT NULL,
    control_state character varying(24) DEFAULT 'MANUAL'::character varying NOT NULL,
    defending smallint DEFAULT 0 NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: mmo_battle_actor_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mmo_battle_actor_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mmo_battle_actor_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mmo_battle_actor_id_seq OWNED BY public.mmo_battle_actor.id;


--
-- Name: mmo_battle_command; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mmo_battle_command (
    id bigint NOT NULL,
    battle_id bigint NOT NULL,
    actor_id bigint NOT NULL,
    action_seq integer NOT NULL,
    request_id character varying(64) NOT NULL,
    command_slot_id bigint NOT NULL,
    payload text NOT NULL,
    ack text NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: mmo_battle_command_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mmo_battle_command_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mmo_battle_command_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mmo_battle_command_id_seq OWNED BY public.mmo_battle_command.id;


--
-- Name: mmo_battle_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mmo_battle_event (
    id bigint NOT NULL,
    battle_id bigint NOT NULL,
    round_no integer NOT NULL,
    visibility character varying(8) NOT NULL,
    recipient_role_id bigint DEFAULT 0 NOT NULL,
    stream_seq bigint NOT NULL,
    state_version bigint NOT NULL,
    critical smallint DEFAULT 1 NOT NULL,
    default_action_applied smallint DEFAULT 0 NOT NULL,
    request_id character varying(64) DEFAULT ''::character varying NOT NULL,
    server_time_ms bigint NOT NULL,
    payload text NOT NULL,
    published_at bigint DEFAULT 0 NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: mmo_battle_event_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mmo_battle_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mmo_battle_event_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mmo_battle_event_id_seq OWNED BY public.mmo_battle_event.id;


--
-- Name: mmo_battle_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mmo_battle_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mmo_battle_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mmo_battle_id_seq OWNED BY public.mmo_battle.id;


--
-- Name: mmo_battle_lease; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mmo_battle_lease (
    id bigint NOT NULL,
    battle_id bigint NOT NULL,
    owner_node character varying(64) NOT NULL,
    owner_epoch bigint DEFAULT 1 NOT NULL,
    lease_until bigint NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: mmo_battle_lease_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mmo_battle_lease_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mmo_battle_lease_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mmo_battle_lease_id_seq OWNED BY public.mmo_battle_lease.id;


--
-- Name: mmo_battle_slot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mmo_battle_slot (
    id bigint NOT NULL,
    battle_id bigint NOT NULL,
    round_no integer NOT NULL,
    actor_id bigint NOT NULL,
    slot_kind character varying(16) DEFAULT 'PRIMARY'::character varying NOT NULL,
    allowed_commands text DEFAULT '[]'::text NOT NULL,
    is_required smallint DEFAULT 1 NOT NULL,
    deadline_at_ms bigint NOT NULL,
    accepted_action_seq integer DEFAULT 0 NOT NULL,
    payload text DEFAULT ''::text NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: mmo_battle_slot_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mmo_battle_slot_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mmo_battle_slot_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mmo_battle_slot_id_seq OWNED BY public.mmo_battle_slot.id;


--
-- Name: mmo_battle_transition; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mmo_battle_transition (
    id bigint NOT NULL,
    role_id bigint NOT NULL,
    scene_session_id bigint NOT NULL,
    battle_id bigint NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    channel_id bigint DEFAULT 0 NOT NULL,
    ticket text DEFAULT ''::text NOT NULL,
    ticket_exp bigint DEFAULT 0 NOT NULL,
    reason text DEFAULT ''::text NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: mmo_battle_transition_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mmo_battle_transition_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mmo_battle_transition_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mmo_battle_transition_id_seq OWNED BY public.mmo_battle_transition.id;


--
-- Name: mmo_map; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mmo_map (
    id bigint NOT NULL,
    name character varying(64) NOT NULL,
    width_cells integer NOT NULL,
    height_cells integer NOT NULL,
    cell_size integer NOT NULL,
    grid text NOT NULL,
    spawn_x integer NOT NULL,
    spawn_y integer NOT NULL,
    status smallint DEFAULT 1 NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: mmo_map_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mmo_map_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mmo_map_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mmo_map_id_seq OWNED BY public.mmo_map.id;


--
-- Name: mmo_npc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mmo_npc (
    id bigint NOT NULL,
    map_id bigint NOT NULL,
    name character varying(64) NOT NULL,
    kind character varying(32) DEFAULT 'dialog'::character varying NOT NULL,
    x integer NOT NULL,
    y integer NOT NULL,
    interact_range integer DEFAULT 3000 NOT NULL,
    dialog text DEFAULT ''::text NOT NULL,
    status smallint DEFAULT 1 NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL,
    encounter text DEFAULT ''::text NOT NULL
);


--
-- Name: mmo_npc_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mmo_npc_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mmo_npc_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mmo_npc_id_seq OWNED BY public.mmo_npc.id;


--
-- Name: mmo_reward_settlement; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mmo_reward_settlement (
    id bigint NOT NULL,
    settlement_request_id character varying(64) NOT NULL,
    battle_id bigint NOT NULL,
    role_id bigint NOT NULL,
    winner_side smallint NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: mmo_reward_settlement_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mmo_reward_settlement_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mmo_reward_settlement_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mmo_reward_settlement_id_seq OWNED BY public.mmo_reward_settlement.id;


--
-- Name: mmo_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mmo_role (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    name character varying(64) NOT NULL,
    status smallint DEFAULT 1 NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL
);


--
-- Name: mmo_role_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mmo_role_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mmo_role_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mmo_role_id_seq OWNED BY public.mmo_role.id;


--
-- Name: mmo_scene_channel; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mmo_scene_channel (
    id bigint NOT NULL,
    scene_ref character varying(64) NOT NULL,
    channel_id bigint NOT NULL,
    status smallint DEFAULT 1 NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL,
    map_id bigint DEFAULT 1 NOT NULL,
    public_scene_seq bigint DEFAULT 0 NOT NULL
);


--
-- Name: mmo_scene_channel_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mmo_scene_channel_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mmo_scene_channel_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mmo_scene_channel_id_seq OWNED BY public.mmo_scene_channel.id;


--
-- Name: mmo_scene_session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mmo_scene_session (
    id bigint NOT NULL,
    role_id bigint NOT NULL,
    scene_ref character varying(64) NOT NULL,
    channel_id bigint NOT NULL,
    session_epoch bigint DEFAULT 1 NOT NULL,
    status smallint DEFAULT 1 NOT NULL,
    last_seen_at bigint DEFAULT 0 NOT NULL,
    created_at bigint DEFAULT 0 NOT NULL,
    updated_at bigint DEFAULT 0 NOT NULL,
    movement_seq bigint DEFAULT 0 NOT NULL,
    entity_version bigint DEFAULT 0 NOT NULL,
    path_id bigint DEFAULT 0 NOT NULL,
    start_x integer DEFAULT 0 NOT NULL,
    start_y integer DEFAULT 0 NOT NULL,
    target_x integer DEFAULT 0 NOT NULL,
    target_y integer DEFAULT 0 NOT NULL,
    path_start_ms bigint DEFAULT 0 NOT NULL,
    speed integer DEFAULT 0 NOT NULL,
    path_points text DEFAULT '[]'::text NOT NULL,
    state character varying(24) DEFAULT 'ACTIVE'::character varying NOT NULL
);


--
-- Name: mmo_scene_session_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.mmo_scene_session_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: mmo_scene_session_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.mmo_scene_session_id_seq OWNED BY public.mmo_scene_session.id;


--
-- Name: mmo_battle id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle ALTER COLUMN id SET DEFAULT nextval('public.mmo_battle_id_seq'::regclass);


--
-- Name: mmo_battle_actor id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle_actor ALTER COLUMN id SET DEFAULT nextval('public.mmo_battle_actor_id_seq'::regclass);


--
-- Name: mmo_battle_command id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle_command ALTER COLUMN id SET DEFAULT nextval('public.mmo_battle_command_id_seq'::regclass);


--
-- Name: mmo_battle_event id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle_event ALTER COLUMN id SET DEFAULT nextval('public.mmo_battle_event_id_seq'::regclass);


--
-- Name: mmo_battle_lease id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle_lease ALTER COLUMN id SET DEFAULT nextval('public.mmo_battle_lease_id_seq'::regclass);


--
-- Name: mmo_battle_slot id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle_slot ALTER COLUMN id SET DEFAULT nextval('public.mmo_battle_slot_id_seq'::regclass);


--
-- Name: mmo_battle_transition id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle_transition ALTER COLUMN id SET DEFAULT nextval('public.mmo_battle_transition_id_seq'::regclass);


--
-- Name: mmo_map id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_map ALTER COLUMN id SET DEFAULT nextval('public.mmo_map_id_seq'::regclass);


--
-- Name: mmo_npc id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_npc ALTER COLUMN id SET DEFAULT nextval('public.mmo_npc_id_seq'::regclass);


--
-- Name: mmo_reward_settlement id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_reward_settlement ALTER COLUMN id SET DEFAULT nextval('public.mmo_reward_settlement_id_seq'::regclass);


--
-- Name: mmo_role id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_role ALTER COLUMN id SET DEFAULT nextval('public.mmo_role_id_seq'::regclass);


--
-- Name: mmo_scene_channel id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_scene_channel ALTER COLUMN id SET DEFAULT nextval('public.mmo_scene_channel_id_seq'::regclass);


--
-- Name: mmo_scene_session id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_scene_session ALTER COLUMN id SET DEFAULT nextval('public.mmo_scene_session_id_seq'::regclass);


--
-- Name: mmo_battle_actor mmo_battle_actor_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle_actor
    ADD CONSTRAINT mmo_battle_actor_pkey PRIMARY KEY (id);


--
-- Name: mmo_battle_command mmo_battle_command_battle_id_actor_id_action_seq_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle_command
    ADD CONSTRAINT mmo_battle_command_battle_id_actor_id_action_seq_key UNIQUE (battle_id, actor_id, action_seq);


--
-- Name: mmo_battle_command mmo_battle_command_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle_command
    ADD CONSTRAINT mmo_battle_command_pkey PRIMARY KEY (id);


--
-- Name: mmo_battle_event mmo_battle_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle_event
    ADD CONSTRAINT mmo_battle_event_pkey PRIMARY KEY (id);


--
-- Name: mmo_battle_lease mmo_battle_lease_battle_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle_lease
    ADD CONSTRAINT mmo_battle_lease_battle_id_key UNIQUE (battle_id);


--
-- Name: mmo_battle_lease mmo_battle_lease_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle_lease
    ADD CONSTRAINT mmo_battle_lease_pkey PRIMARY KEY (id);


--
-- Name: mmo_battle mmo_battle_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle
    ADD CONSTRAINT mmo_battle_pkey PRIMARY KEY (id);


--
-- Name: mmo_battle_slot mmo_battle_slot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle_slot
    ADD CONSTRAINT mmo_battle_slot_pkey PRIMARY KEY (id);


--
-- Name: mmo_battle_transition mmo_battle_transition_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_battle_transition
    ADD CONSTRAINT mmo_battle_transition_pkey PRIMARY KEY (id);


--
-- Name: mmo_map mmo_map_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_map
    ADD CONSTRAINT mmo_map_pkey PRIMARY KEY (id);


--
-- Name: mmo_npc mmo_npc_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_npc
    ADD CONSTRAINT mmo_npc_pkey PRIMARY KEY (id);


--
-- Name: mmo_reward_settlement mmo_reward_settlement_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_reward_settlement
    ADD CONSTRAINT mmo_reward_settlement_pkey PRIMARY KEY (id);


--
-- Name: mmo_reward_settlement mmo_reward_settlement_settlement_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_reward_settlement
    ADD CONSTRAINT mmo_reward_settlement_settlement_request_id_key UNIQUE (settlement_request_id);


--
-- Name: mmo_role mmo_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_role
    ADD CONSTRAINT mmo_role_pkey PRIMARY KEY (id);


--
-- Name: mmo_scene_channel mmo_scene_channel_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_scene_channel
    ADD CONSTRAINT mmo_scene_channel_pkey PRIMARY KEY (id);


--
-- Name: mmo_scene_session mmo_scene_session_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mmo_scene_session
    ADD CONSTRAINT mmo_scene_session_pkey PRIMARY KEY (id);


--
-- Name: idx_mmo_battle_actor_battle; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mmo_battle_actor_battle ON public.mmo_battle_actor USING btree (battle_id);


--
-- Name: idx_mmo_battle_command_request; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mmo_battle_command_request ON public.mmo_battle_command USING btree (battle_id, request_id);


--
-- Name: idx_mmo_battle_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mmo_battle_due ON public.mmo_battle USING btree (phase, deadline_at_ms);


--
-- Name: idx_mmo_battle_event_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mmo_battle_event_pending ON public.mmo_battle_event USING btree (battle_id, published_at);


--
-- Name: idx_mmo_battle_slot_actor_round; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mmo_battle_slot_actor_round ON public.mmo_battle_slot USING btree (battle_id, round_no, actor_id, slot_kind);


--
-- Name: idx_mmo_battle_slot_round; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mmo_battle_slot_round ON public.mmo_battle_slot USING btree (battle_id, round_no);


--
-- Name: idx_mmo_battle_transition_battle; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mmo_battle_transition_battle ON public.mmo_battle_transition USING btree (battle_id);


--
-- Name: idx_mmo_npc_map; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mmo_npc_map ON public.mmo_npc USING btree (map_id, status);


--
-- Name: idx_mmo_role_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mmo_role_name ON public.mmo_role USING btree (name);


--
-- Name: idx_mmo_role_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mmo_role_user ON public.mmo_role USING btree (user_id);


--
-- Name: idx_mmo_scene_channel_cid; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mmo_scene_channel_cid ON public.mmo_scene_channel USING btree (channel_id);


--
-- Name: idx_mmo_scene_channel_ref; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mmo_scene_channel_ref ON public.mmo_scene_channel USING btree (scene_ref);


--
-- Name: idx_mmo_scene_session_active_role; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX idx_mmo_scene_session_active_role ON public.mmo_scene_session USING btree (role_id) WHERE (status = 1);


--
-- Name: idx_mmo_scene_session_role; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mmo_scene_session_role ON public.mmo_scene_session USING btree (role_id, status);


--
-- Name: idx_mmo_scene_session_scene; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_mmo_scene_session_scene ON public.mmo_scene_session USING btree (scene_ref, status);


--
--


SET search_path = public;
