"""Production organizer milestones must be written despite a WARN root logger."""
import pathlib
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
CONFIG = ROOT / 'myhomelib-bootstrap/src/main/resources/logback-spring.xml'


class OrganizerProductionLoggingTest(unittest.TestCase):
    def test_production_organizer_info_milestones_are_enabled(self):
        root = ET.parse(CONFIG).getroot()
        profiles = [node for node in root.findall('springProfile')
                    if 'prod' in node.attrib.get('name', '').split()]
        self.assertEqual(1, len(profiles))
        prod = profiles[0]
        self.assertEqual('WARN', prod.find('root').attrib.get('level'))
        configured = [node for node in prod.findall('logger')
                      if node.attrib.get('name') == 'com.myhomelibcorp.organizer'
                      and node.attrib.get('level') == 'INFO']
        self.assertEqual(1, len(configured), 'Organizer INFO is suppressed by production root WARN')


if __name__ == '__main__':
    unittest.main()
