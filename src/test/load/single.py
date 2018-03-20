# locust.py

import logging
from locust import HttpLocust, TaskSet, task, web
from flask import request
from hubTasks import HubTasks
from hubUser import HubUser

logger = logging.getLogger(__name__)


class SingleUser(HubUser):
    def name(self):
        return "single_test_"

    def start_channel(self, payload, tasks):
        payload["storage"] = "BATCH"

    def start_webhook(self, config):
        # First User - create channel - posts to channel, parallel group callback on channel
        # Second User - create channel - posts to channel, parallel group callback on channel
        # Third User - create channel - posts to channel, minute group callback on channel
        config['parallel'] = 10
        config['batch'] = "SINGLE"
        if config['number'] == 3:
            config['parallel'] = 1
            config['batch'] = "MINUTE"
            # we're not doing this for now ...
            # if self.number == 3:
            #       time.sleep(61)
            #       logger.info("slept on startup for channel 3, now creating callback")


class VerifierTasks(TaskSet):
    hubTasks = None

    def on_start(self):
        self.hubTasks = HubTasks(SingleUser(), self.client)
        self.hubTasks.start()

    @task(1000)
    def write_read(self):
        self.hubTasks.write_read()

    @task(10)
    def hour_query(self):
        self.hubTasks.hour_query()

    @task(10)
    def minute_query(self):
        self.hubTasks.minute_query()

    @task(10)
    def second_query(self):
        self.hubTasks.second_query()

    @task(10)
    def next_previous(self):
        self.hubTasks.next_previous()

    @task(10)
    def verify_callback_length(self):
        self.hubTasks.verify_callback_length(20000)

    @web.app.route("/callback", methods=['GET'])
    def get_channels():
        logger.info(request.remote_addr + ' | ' + request.method + ' | /callback')
        return HubTasks.get_channels()

    @web.app.route("/callback/<channel>", methods=['GET', 'POST'])
    def callback(channel):
        logger.info(request.remote_addr + ' | ' + request.method + ' | /callback/' + channel + ' | ' + request.get_data().strip())
        return HubTasks.callback(channel)


class WebsiteUser(HttpLocust):
    task_set = VerifierTasks
    min_wait = 1
    max_wait = 3

    def __init__(self):
        super(WebsiteUser, self).__init__()
        HubTasks.host = self.host
